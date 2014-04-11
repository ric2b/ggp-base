package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.player.gamer.statemachine.sancho.TreeNode.TreeNodeRef;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

class TreeEdge
{
  /**
   *
   */
  public TreeEdge(int numRoles)
  {
     jointPartialMove = new ForwardDeadReckonLegalMoveInfo[numRoles];
  }

  public String descriptiveName(int roleIndex)
  {
    ForwardDeadReckonLegalMoveInfo moveInfo = jointPartialMove[roleIndex];
    if ( moveInfo.isPseudoNoOp )
    {
      return "<Pseudo no-op>";
    }

    return moveInfo.move.toString();
  }

  int                                      numChildVisits             = 0;
  TreeNodeRef                              child;
  ForwardDeadReckonLegalMoveInfo[]         jointPartialMove;
  TreeEdge                                 selectAs;
  boolean                                  hasCachedPatternMatchValue = false;
  double                                   cachedPatternMatchValue;
}