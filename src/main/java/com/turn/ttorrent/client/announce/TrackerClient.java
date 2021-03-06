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

package com.turn.ttorrent.client.announce;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceResponseMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;

public abstract class TrackerClient {

  /**
   * The set of listeners to announce request answers.
   */
  private final Set<AnnounceResponseListener> listeners;

  protected final SharedTorrent torrent;

  protected final Peer peer;

  protected final URI tracker;

  /**
   * Create tracker client object.
   *
   * @param torrent torrent to interact with
   * @param peer    peer information
   * @param tracker tracker address
   */
  public TrackerClient(final SharedTorrent torrent, final Peer peer, final URI tracker) {
    this.listeners = new HashSet<>();
    this.torrent = torrent;
    this.peer = peer;
    this.tracker = tracker;
  }

  /**
   * Register a new announce response listener.
   *
   * @param listener The listener to register on this announcer events.
   */
  public void register(final AnnounceResponseListener listener) {
    this.listeners.add(listener);
  }

  /**
   * Returns the URI this tracker clients connects to.
   *
   * @return tracker uri
   */
  public URI getTrackerUri() {
    return this.tracker;
  }

  /**
   * Build, send and process a tracker announce request.
   *
   * <p>
   * This function first builds an announce request for the specified event with all the required
   * parameters. Then, the request is made to the tracker and the response analyzed.
   * </p>
   *
   * <p>
   * All registered {@link AnnounceResponseListener} objects are then fired with the decoded
   * payload.
   * </p>
   *
   * @param event        The announce event type (can be AnnounceEvent.NONE for periodic updates).
   * @param inhibitEvent Prevent event listeners from being notified.
   *
   * @throws AnnounceException Unable to announce
   */
  public abstract void announce(final AnnounceRequestMessage.RequestEvent event,
                                final boolean inhibitEvent) throws AnnounceException;

  /**
   * Close any opened announce connection.
   *
   * <p>
   * This method is called by {@link Announce#stop()} to make sure all connections are correctly
   * closed when the announce thread is asked to stop.
   * </p>
   */
  protected void close() {
    // Do nothing by default, but can be overloaded.
  }

  /**
   * Formats an announce event into a usable string.
   *
   * @param event announce request event
   *
   * @return formatted announce event
   */
  protected String formatAnnounceEvent(final AnnounceRequestMessage.RequestEvent event) {
    return AnnounceRequestMessage.RequestEvent.NONE.equals(event)
           ? ""
           : String.format(" %s", event.name());
  }

  /**
   * Handle the announce response from the tracker.
   *
   * <p>
   * Analyzes the response from the tracker and acts on it. If the response is an error, it is
   * logged. Otherwise, the announce response is used to fire the corresponding announce and peer
   * events to all announce listeners.
   * </p>
   *
   * @param message       The incoming {@link TrackerMessage}.
   * @param inhibitEvents Whether or not to prevent events from being fired.
   *
   * @throws AnnounceException Unable to announce
   */
  protected void handleTrackerAnnounceResponse(final TrackerMessage message,
                                               final boolean inhibitEvents)
          throws AnnounceException {
    if (message instanceof ErrorMessage) {
      final ErrorMessage error = (ErrorMessage) message;
      throw new AnnounceException(error.getReason());
    }

    if (!(message instanceof AnnounceResponseMessage)) {
      throw new AnnounceException(String.format("Unexpected tracker message type %s!",
                                                message.getType().name()));
    }

    if (inhibitEvents) {
      return;
    }

    final AnnounceResponseMessage response = (AnnounceResponseMessage) message;
    this.fireAnnounceResponseEvent(response.getComplete(),
                                   response.getIncomplete(),
                                   response.getInterval());
    this.fireDiscoveredPeersEvent(response.getPeers());
  }

  /**
   * Fire the announce response event to all listeners.
   *
   * @param complete   The number of seeders on this torrent.
   * @param incomplete The number of leechers on this torrent.
   * @param interval   The announce interval requested by the tracker.
   */
  protected void fireAnnounceResponseEvent(final int complete,
                                           final int incomplete,
                                           final int interval) {
    for (AnnounceResponseListener listener : this.listeners) {
      listener.handleAnnounceResponse(interval, complete, incomplete);
    }
  }

  /**
   * Fire the new peer discovery event to all listeners.
   *
   * @param peers The list of peers discovered.
   */
  protected void fireDiscoveredPeersEvent(final List<Peer> peers) {
    for (AnnounceResponseListener listener : this.listeners) {
      listener.handleDiscoveredPeers(peers);
    }
  }
}
