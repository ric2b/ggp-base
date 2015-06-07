package org.ggp.base.player.gamer.statemachine.mctsref;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;

public class RAVETestSearchTreeNode extends SearchTreeNode<RAVETestSearchTree>
{
  private double RAVEScore = 0;
  private double RAVECount = 0;
  private static final int PLAYOUT_SAMPLE_DEPTH = 1000;
  private static final double RAVE_EXPLORATION_BIAS = 0.1;

  public RAVETestSearchTreeNode(RAVETestSearchTree xiTree,
                                 ForwardDeadReckonInternalMachineState xiState,
                                 int xiChoosingRole)
  {
    super(xiTree, xiState, xiChoosingRole);
  }

  @Override
  protected void updateScore(SearchTreeNode<RAVETestSearchTree> xiChild, double[] playoutResult)
  {
    for(int i = 0; i < scoreVector.length; i++)
    {
      scoreVector[i] = (scoreVector[i]*numVisits + playoutResult[i])/(numVisits+1);
    }
    numVisits++;

    if ( !tree.selectedMovePath.isEmpty() && tree.updatedMovePath.isEmpty() )
    {
      tree.playoutRAVEWeight = 1;
      for(int i = 0; i < tree.playoutInfo.playoutLength; i++ )
      {
        tree.updatedMovePath.add(tree.playoutInfo.playoutTrace[i]);
      }
    }

    //assert(tree.playoutRAVEWeight != 0);
    //  Update RAVE stats (no decay)
    if ( children != null && children.length > 1 )
    {
      for(ForwardDeadReckonLegalMoveInfo move : tree.updatedMovePath)
      {
        for(int i = 0; i < children.length; i++)
        {
          if ( childMoves[i].move == move.move )
          {
            double weight = tree.playoutRAVEWeight;
            RAVETestSearchTreeNode child = (RAVETestSearchTreeNode)children[i];
            if ( childMoves[i].roleIndex == move.roleIndex )
            {
              child.RAVEScore = (child.RAVEScore*child.RAVECount + weight*playoutResult[choosingRole])/(child.RAVECount+weight);
              child.RAVECount += weight;
            }
//            else
//            {
//              weight *= 0.5;
//              child.RAVEScore = (child.RAVEScore*child.RAVECount + weight*playoutResult[1-choosingRole])/(child.RAVECount+weight);
//              child.RAVECount += weight;
//            }
//            assert(!Double.isNaN(child.RAVEScore));
            break;
          }
        }
      }
    }

    if ( !tree.selectedMovePath.isEmpty())
    {
      tree.updatedMovePath.add(tree.selectedMovePath.remove(tree.selectedMovePath.size()-1));
    }
  }

  private static final double b = 0.05;

  private double RAVEExplorationScore(RAVETestSearchTreeNode child)
  {
    if ( child.numVisits == 0 )
    {
      return 0.5;
    }

    return RAVE_EXPLORATION_BIAS*Math.sqrt(2*Math.log(numVisits) / (child.numVisits));
  }

  @Override
  protected  SearchTreeNode<RAVETestSearchTree> select(ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    if ( complete )
    {
      return this;
    }

    double bestSelectionScore = -Double.MAX_VALUE;
    SearchTreeNode<RAVETestSearchTree> result = null;
    ForwardDeadReckonLegalMoveInfo selectedMove = null;

    for(int i = 0; i < children.length; i++)
    {
      RAVETestSearchTreeNode child = (RAVETestSearchTreeNode)children[i];
      double RAVEWeight = (child.RAVECount)/(child.RAVECount + child.numVisits + b*child.numVisits*child.RAVECount + 1);
      double selectionScore = (1-RAVEWeight)*(explorationScore(child)+exploitationScore(child)) + RAVEWeight*(RAVEExplorationScore(child) + child.RAVEScore/100);

      if ( selectionScore > bestSelectionScore )
      {
        bestSelectionScore = selectionScore;
        result = child;
        selectedMove = childMoves[i];
      }
    }

    jointMove[choosingRole] = selectedMove;

    tree.selectedMovePath.add(selectedMove);

    return result;
  }

  @Override
  public Move getBestMove()
  {
    double bestScore = -Double.MAX_VALUE;
    Move result = null;

    for(int i = 0; i < children.length; i++)
    {
      RAVETestSearchTreeNode child = (RAVETestSearchTreeNode)children[i];
      double RAVEWeight = (child.RAVECount)/(child.RAVECount + child.numVisits + b*child.numVisits*child.RAVECount + 1);
      System.out.println("Move " + childMoves[i].move + " scores: " + child.scoreVector[choosingRole] + " after " + child.numVisits + " visits (RAVE score " + child.RAVEScore + " and count " + child.RAVECount + ") [" + RAVEWeight + "]");
      if ( child.scoreVector[choosingRole] > bestScore || (child.scoreVector[choosingRole] == bestScore && child.complete) )
      {
        bestScore = children[i].scoreVector[choosingRole];
        result = childMoves[i].move;
      }
    }

    return result;
  }

  @Override
  SearchTreeNode<RAVETestSearchTree> createNode(ForwardDeadReckonInternalMachineState xiState,
                                                 int xiChoosingRole)
  {
    return new RAVETestSearchTreeNode(tree, xiState, xiChoosingRole);
  }
}
