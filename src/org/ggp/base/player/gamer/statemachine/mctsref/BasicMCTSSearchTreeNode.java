package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class BasicMCTSSearchTreeNode extends SearchTreeNode
{
  public BasicMCTSSearchTreeNode(SearchTree xiTree,
                                 ForwardDeadReckonInternalMachineState xiState,
                                 int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  boolean updateScore(SearchTreeNode xiChild, double[] playoutResult)
  {
    for(int i = 0; i < scoreVector.length; i++)
    {
      scoreVector[i] = (scoreVector[i]*numVisits + playoutResult[i])/(numVisits+1);
    }

    return true;
  }

  @Override
  SearchTreeNode createNode(ForwardDeadReckonInternalMachineState xiState,
                            int xiChoosingRole)
  {
    // TODO Auto-generated method stub
    return new BasicMCTSSearchTreeNode(tree, xiState, xiChoosingRole);
  }

}
