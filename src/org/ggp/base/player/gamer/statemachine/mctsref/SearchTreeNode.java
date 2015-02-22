package org.ggp.base.player.gamer.statemachine.mctsref;

import java.util.Collection;
import java.util.Random;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public abstract class SearchTreeNode
{
  protected final double EXPLORATION_BIAS = 0.5;
  private final double EPSILON = 0.001;

  protected final SearchTree tree;
  protected SearchTreeNode[] children = null;
  private ForwardDeadReckonLegalMoveInfo[] childMoves = null;
  protected double[] scoreVector;
  protected int numVisits = 0;
  private final ForwardDeadReckonInternalMachineState state;
  protected final int choosingRole;
  private static Random rand = new Random();
  boolean complete = false;

  abstract void updateScore(SearchTreeNode child, double[] playoutResult);

  abstract SearchTreeNode createNode(ForwardDeadReckonInternalMachineState state, int choosingRole);

  public SearchTreeNode(SearchTree xiTree, ForwardDeadReckonInternalMachineState xiState, int xiChoosingRole)
  {
    tree = xiTree;
    state = xiState;
    choosingRole = xiChoosingRole;
    scoreVector = new double[tree.getNumRoles()];

    for(int i = 0; i < scoreVector.length; i++)
    {
      scoreVector[i] = 0;
    }
  }

  public Move getBestMove()
  {
    double bestScore = -Double.MAX_VALUE;
    Move result = null;

    for(int i = 0; i < children.length; i++)
    {
      System.out.println("Move " + childMoves[i].move + " scores: " + children[i].scoreVector[choosingRole] + " after " + children[i].numVisits + " visits");
      if ( children[i].scoreVector[choosingRole] > bestScore || (children[i].scoreVector[choosingRole] == bestScore && children[i].complete) )
      {
        bestScore = children[i].scoreVector[choosingRole];
          result = childMoves[i].move;
      }
    }

    return result;
  }

  private void expand(ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    if ( tree.getStateMachine().isTerminal(state))
    {
      complete = true;

      for(int i = 0; i < scoreVector.length; i++)
      {
        scoreVector[i] = tree.getStateMachine().getGoal(state, tree.getStateMachine().getRoleOrdering().roleIndexToRole(i));
      }
    }
    else
    {
      Role role = tree.getStateMachine().getRoleOrdering().roleIndexToRole(choosingRole);
      Collection<ForwardDeadReckonLegalMoveInfo> legalMoves = tree.getStateMachine().getLegalMoves(state, role);

      children = new SearchTreeNode[legalMoves.size()];
      childMoves = new ForwardDeadReckonLegalMoveInfo[legalMoves.size()];

      int index = 0;

      for(ForwardDeadReckonLegalMoveInfo legalMove : legalMoves)
      {
        ForwardDeadReckonInternalMachineState childState;

        jointMove[choosingRole] = legalMove;

        if ( choosingRole == tree.getNumRoles()-1 )
        {
          childState = new ForwardDeadReckonInternalMachineState(tree.getStateMachine().getInfoSet());
          tree.getStateMachine().getNextState(state, null, jointMove, childState);
        }
        else
        {
          childState = state;
        }

        children[index] = createNode(childState, (choosingRole+1)%tree.getNumRoles());
        childMoves[index++] = legalMove;
      }
    }
  }

  public void grow(double[] playoutResult, ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    SearchTreeNode selectedChild = null;

    if ( !complete )
    {
      //  If there are children select amongst them
      if ( children != null )
      {
        selectedChild = select(jointMove);

        //  Recurse
        selectedChild.grow(playoutResult, jointMove);

        if ( selectedChild.complete )
        {
          if ( selectedChild.scoreVector[choosingRole] > 100 - EPSILON )
          {
            scoreVector = selectedChild.scoreVector;
            complete = true;
          }
          else
          {
            double bestScore = -Double.MAX_VALUE;
            SearchTreeNode bestChild = null;
            boolean allComplete = true;

            for(int i = 0; i < children.length; i++ )
            {
              if ( !children[i].complete )
              {
                allComplete = false;
                break;
              }
              else if ( children[i].scoreVector[choosingRole] > bestScore )
              {
                bestScore = children[i].scoreVector[choosingRole];
                bestChild = children[i];
              }
            }

            if ( allComplete )
            {
              assert(bestChild != null);
              scoreVector = bestChild.scoreVector;
              complete = true;
            }
          }
        }
      }
      else
      {
        //  else expand and playout from one
        expand(jointMove);

        if ( !complete )
        {
          playout(playoutResult);

          selectedChild = select(jointMove);
          selectedChild.playout(playoutResult);

          assert(selectedChild != this);
          assert(selectedChild.numVisits == 0);

          selectedChild.updateScore(null, playoutResult);
          selectedChild.numVisits = 1;
        }
      }
    }

    if ( complete )
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        playoutResult[i] = scoreVector[i];
      }
    }
    else
    {
      assert(selectedChild != null);

      //  Update our score with the playout result
      updateScore(selectedChild, playoutResult);
    }

    numVisits++;
  }

  private SearchTreeNode select(ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    if ( complete )
    {
      return this;
    }

    double bestSelectionScore = -Double.MAX_VALUE;
    SearchTreeNode result = null;
    ForwardDeadReckonLegalMoveInfo selectedMove = null;

    for(int i = 0; i < children.length; i++)
    {
      SearchTreeNode child = children[i];
      double selectionScore = explorationScore(child) + exploitationScore(child);

      if ( selectionScore > bestSelectionScore )
      {
        bestSelectionScore = selectionScore;
        result = child;
        selectedMove = childMoves[i];
      }
    }

    jointMove[choosingRole] = selectedMove;

    return result;
  }

  protected double explorationScore(SearchTreeNode child)
  {
    if ( child.numVisits == 0 )
    {
      return 1000 + rand.nextDouble();
    }

    assert(numVisits>0);
    return EXPLORATION_BIAS*Math.sqrt(2*Math.log(numVisits) / child.numVisits);
  }

  protected double exploitationScore(SearchTreeNode child)
  {
    return child.scoreVector[choosingRole]/100;
  }

  private static int count = 0;
  private void playout(double[] playoutResult)
  {
    //if ( count++ < 30 )
//    if ( rand.nextInt(2) == 0 )
//    {
//      playoutResult[0] = 100;
//      playoutResult[1] = 0;
//    }
//    else
//    {
//      playoutResult[0] = 0;
//      playoutResult[1] = 100;
//    }

    RoleOrdering roleOrdering = tree.getStateMachine().getRoleOrdering();

    tree.getStateMachine().getDepthChargeResult(state, null, roleOrdering.roleIndexToRole(choosingRole), null, null, null, 1000);

    for(int i = 0; i < playoutResult.length; i++)
    {
      playoutResult[i] = tree.getStateMachine().getGoal(roleOrdering.roleIndexToRole(i));
    }
  }
}
