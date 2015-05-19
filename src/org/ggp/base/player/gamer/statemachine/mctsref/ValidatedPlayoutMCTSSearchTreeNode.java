package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class ValidatedPlayoutMCTSSearchTreeNode extends SearchTreeNode<ValidatedPlayoutMCTSSearchTree>
{
  public ValidatedPlayoutMCTSSearchTreeNode(ValidatedPlayoutMCTSSearchTree xiTree,
                                 ForwardDeadReckonInternalMachineState xiState,
                                 int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  protected
  void updateScore(SearchTreeNode xiChild, double[] xiPlayoutResult)
  {
    if ( (xiChild != null && xiChild.complete && xiPlayoutResult[choosingRole] < EPSILON && scoreVector[choosingRole] > EPSILON) || tree.mSuppressBackProp )
    {
      tree.mSuppressBackProp = true;
    }
    else
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = (scoreVector[i]*numVisits + xiPlayoutResult[i])/(numVisits+1);
      }
    }
    numVisits++;
  }

  @Override
  SearchTreeNode createNode(ForwardDeadReckonInternalMachineState xiState,
                            int xiChoosingRole)
  {
    return new ValidatedPlayoutMCTSSearchTreeNode(tree, xiState, xiChoosingRole);
  }

  static final int[] stats = new int[2];
  @Override
  protected void playout(double[] playoutResult)
  {
    RoleOrdering roleOrdering = tree.getStateMachine().getRoleOrdering();

    tree.getStateMachine().getDepthChargeResult(state, null, roleOrdering.roleIndexToRole(choosingRole), stats, null, null, 1000);

    for(int i = 0; i < playoutResult.length; i++)
    {
      playoutResult[i] = tree.getStateMachine().getGoal(roleOrdering.roleIndexToRole(i));
    }

    if ( stats[0] < 2 )
    {
      complete = true;
      scoreVector[0] = playoutResult[0];
      scoreVector[1] = playoutResult[1];
    }
//    ValidatedPlayoutMCTSSearchTree theTree = (ValidatedPlayoutMCTSSearchTree)tree;
//
//    int score = (choosingRole == 0 ? theTree.getLocalSearcher().completeResultSearchToDepthFromSeed(state, null, PLAYOUT_VALIDATION_DEPTH) : 50);
//
//    if ( score == 50 )
//    {
//      super.playout(playoutResult);
//    }
//    else
//    {
//      playoutResult[0] = score;
//      playoutResult[1] = 100-score;
//      scoreVector[0] = score;
//      scoreVector[1] = 100-score;
//      complete = true;
//    }
  }
}
