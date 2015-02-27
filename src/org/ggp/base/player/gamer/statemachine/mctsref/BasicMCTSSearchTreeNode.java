package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class BasicMCTSSearchTreeNode extends SearchTreeNode<BasicMCTSSearchTree>
{
  public BasicMCTSSearchTreeNode(BasicMCTSSearchTree xiTree,
                                 ForwardDeadReckonInternalMachineState xiState,
                                 int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  protected void updateScore(SearchTreeNode<BasicMCTSSearchTree> xiChild, double[] playoutResult)
  {
    numVisits++;
    for(int i = 0; i < scoreVector.length; i++)
    {
      scoreVector[i] = (scoreVector[i]*numVisits + playoutResult[i])/(numVisits+1);
    }
  }

  @Override
  SearchTreeNode<BasicMCTSSearchTree> createNode(ForwardDeadReckonInternalMachineState xiState,
                                                 int xiChoosingRole)
  {
    return new BasicMCTSSearchTreeNode(tree, xiState, xiChoosingRole);
  }
}
