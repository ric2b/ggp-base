package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;


/**
 * @author steve
 * Interface that allows querying of whether a move is included in the interface
 * implementor's local search region
 */
public interface LocalRegionDefiner
{
  /**
   * @param move
   * @return whether the move is part of the local region
   */
  boolean isLocal(ForwardDeadReckonLegalMoveInfo move);
}