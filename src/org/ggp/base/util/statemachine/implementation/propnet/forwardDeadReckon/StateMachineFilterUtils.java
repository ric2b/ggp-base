package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.Iterator;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

/**
 * @author steve
 * Utils class that serves to provide a placeholder for static methods used to apply
 * filters
 */
public class StateMachineFilterUtils
{

  /**
   * Get the next move with respect to a specified filter - inserts pseudo-noops if required
   * @param xiFilter - filter to enumerate moves with respect to
   * @param itr - iterator on the collection of all moves
   * @return next move
   */
  public static ForwardDeadReckonLegalMoveInfo nextFilteredMove(StateMachineFilter xiFilter,
                                                          Iterator<ForwardDeadReckonLegalMoveInfo> itr)
  {
    if ( xiFilter == null )
    {
      return itr.next();
    }

    return xiFilter.nextFilteredMove(itr);
  }

  /**
   * @return the number of moves in the specified collection that are valid wih respect to the specified filter.
   *
   * @param xiMoves  - the moves.
   * @param xiFilter - the filter.
   * @param includeForcedPseudoNoops - whether to include forced pseudo-noops
   */
  public static int getFilteredSize(Collection<ForwardDeadReckonLegalMoveInfo> xiMoves, StateMachineFilter xiFilter, boolean includeForcedPseudoNoops)
  {
    if (xiFilter == null)
    {
      return xiMoves.size();
    }

    return xiFilter.getFilteredMovesSize(xiMoves, includeForcedPseudoNoops);
  }

}
