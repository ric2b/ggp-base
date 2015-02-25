package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class ARRSearchTreeNode extends SearchTreeNode
{
  public ARRSearchTreeNode(SearchTree xiTree,
                           ForwardDeadReckonInternalMachineState xiState,
                           int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  void updateScore(SearchTreeNode xiChild, double[] playoutResult)
  {
    for(int i = 0; i < scoreVector.length; i++)
    {
      scoreVector[i] = (scoreVector[i]*numVisits + playoutResult[i])/(numVisits+1);
    }
  }

  @Override
  SearchTreeNode createNode(ForwardDeadReckonInternalMachineState xiState,
                            int xiChoosingRole)
  {
    return new ARRSearchTreeNode(tree, xiState, xiChoosingRole);
  }
}
