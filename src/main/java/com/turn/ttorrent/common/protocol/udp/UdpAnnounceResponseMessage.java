/*
 * Copyright (C) 2012 Turn, Inc.
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

package com.turn.ttorrent.common.protocol.udp;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import lombok.Getter;

/**
 * The announce response message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
@Getter
public class UdpAnnounceResponseMessage extends UdpTrackerMessage.UdpTrackerResponseMessage
        implements TrackerMessage.AnnounceResponseMessage {

  private static final int UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE = 20;

  private final int actionId = Type.ANNOUNCE_RESPONSE.getId();

  private final int transactionId;

  private final int interval;

  private final int complete;

  private final int incomplete;

  private final List<Peer> peers;

  private UdpAnnounceResponseMessage(final ByteBuffer data, final int transactionId,
                                     final int interval, final int complete,
                                     final int incomplete, final List<Peer> peers) {
    super(Type.ANNOUNCE_REQUEST, data);
    this.transactionId = transactionId;
    this.interval = interval;
    this.complete = complete;
    this.incomplete = incomplete;
    this.peers = peers;
  }

  /**
   * Parse inbound response message.
   *
   * @param data tracker buffer
   *
   * @return HTTP request message
   *
   * @throws MessageValidationException Invalid message
   */
  public static UdpAnnounceResponseMessage parse(final ByteBuffer data)
          throws MessageValidationException {
    if (data.remaining() < UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE
        || (data.remaining() - UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE) % 6 != 0) {
      throw new MessageValidationException("Invalid announce response message size!");
    }

    if (data.getInt() != Type.ANNOUNCE_RESPONSE.getId()) {
      throw new MessageValidationException("Invalid action code for announce response!");
    }

    final int transactionId = data.getInt();
    final int interval = data.getInt();
    final int incomplete = data.getInt();
    final int complete = data.getInt();

    final List<Peer> peers = new LinkedList<>();
    // the line below replaces this: for (int i=0; i < data.remaining() / 6; i++)
    // That for loop fails when data.remaining() is 6, even if data.remaining() / 6 is
    // placed in parentheses.  The reason why it fails is not clear.  Replacing it
    // with while (data.remaining() > 5) works however.
    while (data.remaining() > 5) {
      try {
        final byte[] ipBytes = new byte[4];
        data.get(ipBytes);
        final InetAddress ip = InetAddress.getByAddress(ipBytes);
        final int port = (0xFF & (int) data.get()) << 8 | (0xFF & (int) data.get());
        peers.add(new Peer(new InetSocketAddress(ip, port)));
      } catch (final UnknownHostException uhe) {
        throw new MessageValidationException("Invalid IP address in announce request!");
      }
    }

    return new UdpAnnounceResponseMessage(data,
                                          transactionId,
                                          interval,
                                          complete,
                                          incomplete,
                                          peers);
  }

  /**
   * Create tracker error message.
   *
   * @param transactionId transaction id
   * @param interval      communication interval
   * @param complete      complete number
   * @param incomplete    incomplete number
   * @param peers         peer information
   *
   * @return message
   */
  public static UdpAnnounceResponseMessage craft(final int transactionId,
                                                 final int interval,
                                                 final int complete,
                                                 final int incomplete,
                                                 final List<Peer> peers) {
    final ByteBuffer data = ByteBuffer
            .allocate(UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE + 6 * peers.size());
    data.putInt(Type.ANNOUNCE_RESPONSE.getId());
    data.putInt(transactionId);
    data.putInt(interval);

    /**
     * Leechers (incomplete) are first, before seeders (complete) in the packet.
     */
    data.putInt(incomplete);
    data.putInt(complete);

    for (Peer peer : peers) {
      final byte[] ip = peer.getRawIp();
      if (ip == null || ip.length != 4) {
        continue;
      }

      data.put(ip);
      data.putShort((short) peer.getPort());
    }

    return new UdpAnnounceResponseMessage(data,
                                          transactionId,
                                          interval,
                                          complete,
                                          incomplete,
                                          peers);
  }
}
