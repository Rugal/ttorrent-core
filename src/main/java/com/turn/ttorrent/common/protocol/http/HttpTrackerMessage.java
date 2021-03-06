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

package com.turn.ttorrent.common.protocol.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import com.turn.ttorrent.bcodec.BeDecoder;
import com.turn.ttorrent.bcodec.BeValue;
import com.turn.ttorrent.common.protocol.TrackerMessage;

/**
 * Base class for HTTP tracker messages.
 *
 * @author mpetazzoni
 */
public abstract class HttpTrackerMessage extends TrackerMessage {

  protected HttpTrackerMessage(final Type type, final ByteBuffer data) {
    super(type, data);
  }

  /**
   * Parse tracker buffer.
   *
   * @param data tracker message buffer
   *
   * @return message
   *
   * @throws IOException                unable to bdecode
   * @throws MessageValidationException invalid message
   */
  public static HttpTrackerMessage parse(final ByteBuffer data) throws IOException,
                                                                       MessageValidationException {
    final BeValue decoded = BeDecoder.bdecode(data);
    if (decoded == null) {
      throw new MessageValidationException("Could not decode tracker message (not B-encoded?)!");
    }

    final Map<String, BeValue> params = decoded.getMap();

    if (params.containsKey("info_hash")) {
      return HttpAnnounceRequestMessage.parse(data);
    }
    if (params.containsKey("peers")) {
      return HttpAnnounceResponseMessage.parse(data);
    }
    if (params.containsKey("failure reason")) {
      return HttpTrackerErrorMessage.parse(data);
    }

    throw new MessageValidationException("Unknown HTTP tracker message!");
  }
}
