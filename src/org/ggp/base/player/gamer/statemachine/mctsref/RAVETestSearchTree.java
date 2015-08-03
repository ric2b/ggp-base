package org.ggp.base.player.gamer.statemachine.mctsref;

import java.util.ArrayList;
import java.util.List;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public class RAVETestSearchTree extends SearchTree
{
  List<ForwardDeadReckonLegalMoveInfo> selectedMovePath = new ArrayList<>();
  List<ForwardDeadReckonLegalMoveInfo> updatedMovePath = new ArrayList<>();
  double playoutRAVEWeight;

  public RAVETestSearchTree(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);
  }

  @Override
  protected SearchTreeNode<RAVETestSearchTree> createRootTreeNode(ForwardDeadReckonInternalMachineState xiRootState)
  {
    return new RAVETestSearchTreeNode(this, xiRootState, 0);
  }
}
