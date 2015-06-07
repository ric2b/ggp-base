package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;

public abstract class SearchTree
{
  private final ForwardDeadReckonPropnetStateMachine stateMachine;
  private final int numRoles;
  private SearchTreeNode<SearchTree> root;
  private final double[] playoutScoreBuffer;
  private final ForwardDeadReckonLegalMoveInfo[] jointMoveBuffer;
  final ForwardDeadReckonLegalMoveInfo[]  playoutList;
  final ForwardDeadReckonPropnetStateMachine.PlayoutInfo playoutInfo;

  protected abstract SearchTreeNode createRootTreeNode(ForwardDeadReckonInternalMachineState rootState);

  public SearchTree(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    stateMachine = xiStateMachine;
    root = null;

    numRoles = stateMachine.getRoles().length;
    playoutScoreBuffer = new double[numRoles];
    jointMoveBuffer = new ForwardDeadReckonLegalMoveInfo[numRoles];
    playoutList = new ForwardDeadReckonLegalMoveInfo[500];
    playoutInfo = stateMachine.new PlayoutInfo();
    playoutInfo.cutoffDepth = 500;
    playoutInfo.playoutTrace = playoutList;
  }

  public ForwardDeadReckonPropnetStateMachine getStateMachine()
  {
    return stateMachine;
  }

  public int getNumRoles()
  {
    return numRoles;
  }

  public void clear(ForwardDeadReckonInternalMachineState rootState)
  {
    root = createRootTreeNode(rootState);
  }

  public boolean isSolved()
  {
    return root != null && root.complete;
  }

  public void grow()
  {
    root.grow(playoutScoreBuffer, jointMoveBuffer);
  }

  public Move getBestMove()
  {
    return root.getBestMove();
  }
}
