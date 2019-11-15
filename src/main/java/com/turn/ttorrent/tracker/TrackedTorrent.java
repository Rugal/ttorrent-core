/*
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.tracker;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage.RequestEvent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

/**
 * Tracked torrents are torrent for which we don't expect to have data files for.
 *
 * <p>
 * {@link TrackedTorrent} objects are used by the BitTorrent tracker to represent a torrent that is
 * announced by the tracker. As such, it is not expected to point to any valid local data like. It
 * also contains some additional information used by the tracker to keep track of which peers
 * exchange on it, etc.
 * </p>
 *
 * @author mpetazzoni
 */
@Data
@Slf4j
public class TrackedTorrent extends Torrent {

  /**
   * Minimum announce interval requested from peers, in seconds.
   */
  public static final int MIN_ANNOUNCE_INTERVAL_SECONDS = 5;

  /**
   * Default number of peers included in a tracker response.
   */
  private static final int DEFAULT_ANSWER_NUM_PEERS = 30;

  /**
   * Default announce interval requested from peers, in seconds.
   */
  private static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 10;

  private int answerPeers;

  /**
   * Get the announce interval for this torrent.
   */
  private int announceInterval;

  /**
   * Peers currently exchanging on this torrent.
   */
  private ConcurrentMap<String, TrackedPeer> peers;

  /**
   * Create a new tracked torrent from meta-info binary data.
   *
   * @param torrent The meta-info byte data.
   *
   * @throws IOException              When the info dictionary can't be encoded and hashed back to
   *                                  create the torrent's SHA-1 hash.
   * @throws NoSuchAlgorithmException unable to find specified algorithm
   */
  public TrackedTorrent(final byte[] torrent) throws IOException, NoSuchAlgorithmException {
    super(torrent, false);

    this.peers = new ConcurrentHashMap<>();
    this.answerPeers = TrackedTorrent.DEFAULT_ANSWER_NUM_PEERS;
    this.announceInterval = TrackedTorrent.DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
  }

  public TrackedTorrent(final Torrent torrent) throws IOException, NoSuchAlgorithmException {
    this(torrent.getEncoded());
  }

  /**
   * Add a peer exchanging on this torrent.
   *
   * @param peer The new Peer involved with this torrent.
   */
  public void addPeer(final TrackedPeer peer) {
    this.peers.put(peer.getHexPeerId(), peer);
  }

  /**
   * Retrieve a peer exchanging on this torrent.
   *
   * @param peerId The hexadecimal representation of the peer's ID.
   *
   * @return get tracked peer by peer id
   */
  public TrackedPeer getPeer(final String peerId) {
    return this.peers.get(peerId);
  }

  /**
   * Remove a peer from this torrent's swarm.
   *
   * @param peerId The hexadecimal representation of the peer's ID.
   *
   * @return tracked peer by peer id
   */
  public TrackedPeer removePeer(final String peerId) {
    return this.peers.remove(peerId);
  }

  /**
   * Count the number of seeders (peers in the COMPLETED state) on this torrent.
   *
   * @return number of seeders
   */
  public int seeders() {
    int count = 0;
    count = this.peers.values().stream()
            .filter(TrackedPeer::isCompleted)
            .map((_item) -> 1)
            .reduce(count, Integer::sum);
    return count;
  }

  /**
   * Count the number of leechers (non-COMPLETED peers) on this torrent.
   *
   * @return number of leechers
   */
  public int leechers() {
    int count = 0;
    count = this.peers.values().stream()
            .filter((peer) -> !peer.isCompleted())
            .map((_item) -> 1)
            .reduce(count, Integer::sum);
    return count;
  }

  /**
   * Remove unfresh peers from this torrent. Collect and remove all non-fresh peers from this
   * torrent. This is usually called by the periodic peer collector of the BitTorrent tracker.
   */
  public void collectUnfreshPeers() {
    for (TrackedPeer peer : this.peers.values()) {
      if (!peer.isFresh()) {
        this.peers.remove(peer.getHexPeerId());
      }
    }
  }

  /**
   * Set the announce interval for this torrent.
   *
   * @param interval New announce interval, in seconds.
   */
  public void setAnnounceInterval(final int interval) {
    if (interval <= 0) {
      throw new IllegalArgumentException("Invalid announce interval");
    }

    this.announceInterval = interval;
  }

  /**
   * Update this torrent's swarm from an announce event.
   *
   * <p>
   * This will automatically create a new peer on a 'started' announce event, and remove the peer on
   * a 'stopped' announce event.
   * </p>
   *
   * @param event      The reported event. If <em>null</em>, means a regular interval announce
   *                   event, as defined in the BitTorrent specification.
   * @param peerId     The byte-encoded peer ID.
   * @param hexPeerId  The hexadecimal representation of the peer's ID.
   * @param ip         The peer's IP address.
   * @param port       The peer's inbound port.
   * @param uploaded   The peer's reported uploaded byte count.
   * @param downloaded The peer's reported downloaded byte count.
   * @param left       The peer's reported left to download byte count.
   *
   * @return The peer that sent us the announce request.
   *
   * @throws UnsupportedEncodingException character encoding not supported
   */
  public TrackedPeer update(final RequestEvent event,
                            final ByteBuffer peerId,
                            final String hexPeerId,
                            final String ip,
                            final int port,
                            final long uploaded,
                            final long downloaded,
                            final long left) throws UnsupportedEncodingException {
    final TrackedPeer peer;
    TrackedPeer.PeerState state = TrackedPeer.PeerState.UNKNOWN;

    switch (event) {
      case STARTED:
        peer = new TrackedPeer(this, ip, port, peerId);
        state = TrackedPeer.PeerState.STARTED;
        this.addPeer(peer);
        break;
      case STOPPED:
        peer = this.removePeer(hexPeerId);
        state = TrackedPeer.PeerState.STOPPED;
        break;
      case COMPLETED:
        peer = this.getPeer(hexPeerId);
        state = TrackedPeer.PeerState.COMPLETED;
        break;
      case NONE:
        peer = this.getPeer(hexPeerId);
        state = TrackedPeer.PeerState.STARTED;
        break;
      default:
        throw new IllegalArgumentException("Unexpected announce event type!");
    }

    peer.update(state, uploaded, downloaded, left);
    return peer;
  }

  /**
   * Get a list of peers we can return in an announce response for this torrent.
   *
   * @param peer The peer making the request, so we can exclude it from the list of returned peers.
   *
   * @return A list of peers we can include in an announce response.
   */
  public List<Peer> getSomePeers(final TrackedPeer peer) {
    final List<Peer> peers = new LinkedList<>();

    // Extract answerPeers random peers
    final List<TrackedPeer> candidates = new LinkedList<>(this.peers.values());
    Collections.shuffle(candidates);

    int count = 0;
    for (TrackedPeer candidate : candidates) {
      // Collect unfresh peers, and obviously don't serve them as well.
      if (!candidate.isFresh()
          || candidate.looksLike(peer) && !candidate.equals(peer)) {
        LOG.debug("Collecting stale peer {}...", candidate);
        this.peers.remove(candidate.getHexPeerId());
        continue;
      }

      // Don't include the requesting peer in the answer.
      if (peer.looksLike(candidate)) {
        continue;
      }

      // Collect unfresh peers, and obviously don't serve them as well.
      if (!candidate.isFresh()) {
        LOG.debug("Collecting stale peer {}...", candidate.getHexPeerId());
        this.peers.remove(candidate.getHexPeerId());
        continue;
      }

      // Only serve at most ANSWER_NUM_PEERS peers
      if (count++ > this.answerPeers) {
        break;
      }

      peers.add(candidate);
    }

    return peers;
  }

  /**
   * Load a tracked torrent from the given torrent file.
   *
   * @param torrent The abstract {@link File} object representing the
   * {@code .torrent} file to load.
   *
   * @return loaded torrent
   *
   * @throws IOException              When the torrent file cannot be read.
   * @throws NoSuchAlgorithmException Unable to find specified algorithm
   */
  public static TrackedTorrent load(final File torrent) throws IOException,
                                                               NoSuchAlgorithmException {
    return new TrackedTorrent(FileUtils.readFileToByteArray(torrent));
  }
}
