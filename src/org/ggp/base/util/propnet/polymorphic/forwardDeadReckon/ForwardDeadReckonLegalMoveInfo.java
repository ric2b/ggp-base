
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;

/**
 * @author steve
 *  Meta information about a legal move in the context
 *  of a particular propnet
 */
public class ForwardDeadReckonLegalMoveInfo
{
  /**
   * GDL move
   */
  public Move                         move;
  /**
   * Role the move is a move for
   */
  public int                          roleIndex;
  /**
   * Index in the master move list of this move info
   * Note that this is now global and shared by all propnets
   */
  public int                          masterIndex;
  /**
   * DOES proposition for this move
   */
  public ForwardDeadReckonProposition inputProposition;
  /**
   * Factor to which this move belongs in a factorized game, else null
   */
  public Factor                       factor;
  /**
   * Whether this is an artificial noop inserted during game factorization
   */
  public boolean                      isPseudoNoOp;
  /**
   * Whether this move amounts to a nop-op on the goal-relevant part of the state
   */
  public boolean                      isVirtualNoOp;

  /**
   * Construct a new move info instance
   */
  public ForwardDeadReckonLegalMoveInfo()
  {
    isPseudoNoOp = false;
  }

  /**
   * Construct a new move info instance specifying whether it is a pseudo-noop
   * @param moveIsPseudoNoOp whether the move is an artificial noop introduced during factorization
   */
  public ForwardDeadReckonLegalMoveInfo(boolean moveIsPseudoNoOp)
  {
    this.isPseudoNoOp = moveIsPseudoNoOp;
  }
}
