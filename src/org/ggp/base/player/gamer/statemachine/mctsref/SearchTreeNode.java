package org.ggp.base.player.gamer.statemachine.mctsref;

import java.util.Collection;
import java.util.Random;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public abstract class SearchTreeNode<TreeType extends SearchTree>
{
  protected static final double EPSILON = 0.001;
  private static final Random RAND = new Random();

  protected final double EXPLORATION_BIAS = 0.5;

  protected final TreeType tree;
  protected SearchTreeNode<TreeType>[] children = null;
  private ForwardDeadReckonLegalMoveInfo[] childMoves = null;
  protected double[] scoreVector;
  protected int numVisits = 0;
  protected final ForwardDeadReckonInternalMachineState state;
  protected final int choosingRole;
  boolean complete = false;

  protected abstract void updateScore(SearchTreeNode<TreeType> child, double[] playoutResult);

  abstract SearchTreeNode<TreeType> createNode(ForwardDeadReckonInternalMachineState xiState, int xiChoosingRole);

  public SearchTreeNode(TreeType xiTree, ForwardDeadReckonInternalMachineState xiState, int xiChoosingRole)
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

  @SuppressWarnings("unchecked")
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
    SearchTreeNode<TreeType> selectedChild = null;

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
          processChildCompletion(selectedChild);
        }
      }
      else
      {
        //  else expand and playout from one
        expand(jointMove);

        if ( !complete )
        {
          //playout(playoutResult);

          selectedChild = select(jointMove);

          if ( selectedChild.choosingRole == 0 )
          {
            selectedChild.playout(playoutResult);

            assert(selectedChild != this);
            assert(selectedChild.numVisits == 0);

            selectedChild.updateScore(null, playoutResult);
            selectedChild.numVisits = 1;
          }
          else
          {
            //  Recurse down to a complete joint move.  This
            //  is necessary because we cannot playout from a node
            //  which has a partial joint move, since this will have the
            //  same state as its parent, and any playout would be from
            //  that state and not respect the move choice made from the
            //  parent to this node
            selectedChild.grow(playoutResult, jointMove);
          }

          if ( selectedChild.complete )
          {
            processChildCompletion(selectedChild);
          }
        }
      }
    }

    if ( complete )
    {
      for(int i = 0; i < scoreVector.length; i++)
      {
        playoutResult[i] = scoreVector[i];
      }

      numVisits++;
    }
    else
    {
      assert(selectedChild != null);

      //  Update our score with the playout result
      updateScore(selectedChild, playoutResult);
    }
  }

  private void processChildCompletion(SearchTreeNode<TreeType> selectedChild)
  {
    if ( selectedChild.scoreVector[choosingRole] > 100 - EPSILON )
    {
      scoreVector = selectedChild.scoreVector;
      complete = true;
    }
    else
    {
      double bestScore = -Double.MAX_VALUE;
      SearchTreeNode<TreeType> bestChild = null;
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

  protected SearchTreeNode<TreeType> select(ForwardDeadReckonLegalMoveInfo[] jointMove)
  {
    if ( complete )
    {
      return this;
    }

    double bestSelectionScore = -Double.MAX_VALUE;
    SearchTreeNode<TreeType> result = null;
    ForwardDeadReckonLegalMoveInfo selectedMove = null;

    for(int i = 0; i < children.length; i++)
    {
      SearchTreeNode<TreeType> child = children[i];
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

  protected double explorationScore(SearchTreeNode<TreeType> child)
  {
    if ( child.numVisits == 0 )
    {
      return 1000 + RAND.nextDouble();
    }

    assert(numVisits>0);
    return EXPLORATION_BIAS*Math.sqrt(2*Math.log(numVisits) / child.numVisits);
  }

  protected double exploitationScore(SearchTreeNode<TreeType> child)
  {
    return child.scoreVector[choosingRole]/100;
  }

  protected void playout(double[] playoutResult)
  {
    RoleOrdering roleOrdering = tree.getStateMachine().getRoleOrdering();

    tree.getStateMachine().getDepthChargeResult(state, null, roleOrdering.roleIndexToRole(choosingRole), null, null, null, 1000);

    for(int i = 0; i < playoutResult.length; i++)
    {
      playoutResult[i] = tree.getStateMachine().getGoal(roleOrdering.roleIndexToRole(i));
    }
  }
}
