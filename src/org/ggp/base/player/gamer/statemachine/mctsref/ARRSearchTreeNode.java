package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

public class ARRSearchTreeNode extends SearchTreeNode<ARRSearchTree>
{
  private boolean mStopBackPropHere;

  public ARRSearchTreeNode(ARRSearchTree xiTree,
                           ForwardDeadReckonInternalMachineState xiState,
                           int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  protected SearchTreeNode<ARRSearchTree> select(ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    mStopBackPropHere = false;

    if (complete)
    {
      return this;
    }

    // Find the best child on exploitation alone.
    double lBestExploitationScore = -Double.MAX_VALUE;
    for(int i = 0; i < children.length; i++)
    {
      SearchTreeNode<ARRSearchTree> child = children[i];
      double lExploitationScore = exploitationScore(child);

      if (lExploitationScore > lBestExploitationScore)
      {
        lBestExploitationScore = lExploitationScore;
      }
    }

    // Do the regular selection.
    SearchTreeNode<ARRSearchTree> lSelected = super.select(jointMove);

    // If the exploitation value of the selected child isn't sufficiently large, assume that it wouldn't be chosen and
    // block back-prop at this point.
    if (exploitationScore(lSelected) < (lBestExploitationScore - 0.02))
    {
      mStopBackPropHere = true;
    }

    return lSelected;
  }

  @Override
  protected void updateScore(SearchTreeNode<ARRSearchTree> xiChild, double[] playoutResult)
  {
    if ((tree.mSuppressBackProp) || (mStopBackPropHere))
    {
      tree.mSuppressBackProp = true;
      return;
    }

    for(int i = 0; i < scoreVector.length; i++)
    {
      scoreVector[i] = (scoreVector[i]*numVisits + playoutResult[i])/(numVisits+1);
    }
  }

  @Override
  SearchTreeNode<ARRSearchTree> createNode(ForwardDeadReckonInternalMachineState xiState,
                                           int xiChoosingRole)
  {
    return new ARRSearchTreeNode(tree, xiState, xiChoosingRole);
  }
}
