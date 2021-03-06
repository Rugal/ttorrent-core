package com.turn.ttorrent.client.strategy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Random;
import java.util.SortedSet;

import com.turn.ttorrent.client.Piece;

/**
 * The default request strategy implementation- rarest first.
 *
 * @author cjmalloy
 *
 */
public class RequestStrategyImplRarest implements RequestStrategy {

  /**
   * Randomly select the next piece to download from a peer from the RAREST_PIECE_JITTER available
   * from it.
   */
  private static final int RAREST_PIECE_JITTER = 42;

  private final Random random;

  public RequestStrategyImplRarest() {
    this.random = new Random(System.currentTimeMillis());
  }

  @Override
  public Piece choosePiece(final SortedSet<Piece> rarest,
                           final BitSet interesting,
                           final Piece[] pieces) {
    // Extract the RAREST_PIECE_JITTER rarest pieces from the interesting
    // pieces of this peer.
    final ArrayList<Piece> choice = new ArrayList<>(RAREST_PIECE_JITTER);
    synchronized (rarest) {
      for (Piece piece : rarest) {
        if (interesting.get(piece.getIndex())) {
          choice.add(piece);
          if (choice.size() >= RAREST_PIECE_JITTER) {
            break;
          }
        }
      }
    }

    if (choice.isEmpty()) {
      return null;
    }

    return choice.get(this.random.nextInt(Math.min(choice.size(), RAREST_PIECE_JITTER)));
  }
}
