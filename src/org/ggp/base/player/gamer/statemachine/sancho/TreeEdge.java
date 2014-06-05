package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

class TreeEdge
{
  /**
   *
   */
  public TreeEdge(ForwardDeadReckonLegalMoveInfo move)
  {
    partialMove = move;
  }

  public String descriptiveName()
  {
    if ( partialMove.isPseudoNoOp )
    {
      return "<Pseudo no-op>";
    }

    return partialMove.move.toString();
  }

  int                                      numChildVisits             = 0;
  TreeNodeRef                              child                      = null;
  final ForwardDeadReckonLegalMoveInfo     partialMove;
  TreeEdge                                 selectAs;
  double                                   explorationAmplifier       = 0;
}