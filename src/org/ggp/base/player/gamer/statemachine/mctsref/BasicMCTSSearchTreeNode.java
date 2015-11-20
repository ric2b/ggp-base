package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration;
import org.ggp.base.player.gamer.statemachine.sancho.MachineSpecificConfiguration.CfgItem;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class BasicMCTSSearchTreeNode extends SearchTreeNode<BasicMCTSSearchTree>
{
  private static final double LEARNING_RATE = 0.01;
  private static final boolean USE_MOVING_AVERAGE =
    MachineSpecificConfiguration.getCfgBool(CfgItem.REF_USES_MOVING_AVERAGE);

  public BasicMCTSSearchTreeNode(BasicMCTSSearchTree xiTree,
                                 ForwardDeadReckonInternalMachineState xiState,
                                 int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  protected void updateScore(SearchTreeNode<BasicMCTSSearchTree> xiChild, double[] playoutResult)
  {
    if (USE_MOVING_AVERAGE)
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        if (numVisits == 0)
        {
          scoreVector[i] = 50;
        }

        scoreVector[i] = (scoreVector[i] * (1.0 - LEARNING_RATE)) + (playoutResult[i] * LEARNING_RATE);
      }
      numVisits++;
    }
    else
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = (scoreVector[i]*numVisits + playoutResult[i])/(numVisits+1);
      }
      numVisits++;
    }
  }

  @Override
  SearchTreeNode<BasicMCTSSearchTree> createNode(ForwardDeadReckonInternalMachineState xiState,
                                                 int xiChoosingRole)
  {
    return new BasicMCTSSearchTreeNode(tree, xiState, xiChoosingRole);
  }
}
