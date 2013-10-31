package org.ggp.base.player.gamer.statemachine.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponentFactory;
import org.ggp.base.util.propnet.polymorphic.runtimeOptimized.RuntimeOptimizedComponentFactory;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestPropnetStateMachine;

public class TestMinimaxGamer extends SampleGamer {

	private TestPropnetStateMachine	underlyingStateMachine;
	
	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		//underlyingStateMachine.recreate(new LearningComponentFactory());
        MachineState initial = underlyingStateMachine.getInitialState();
        
		underlyingStateMachine.setRandomSeed(1);
		LearningComponent.getCount = 0;
		underlyingStateMachine.performDepthCharge(initial,null);
		System.out.println("#pre-learning gets for one depth charge: " + LearningComponent.getCount);
		
		if ( true )
		{
		    final int learningCount = 5;
		    
			for(int i = 0; i < learningCount; i++)
			{
				stateMachineSelectMoveInternal(Math.min(System.currentTimeMillis()+2000, timeout), false);
				
				underlyingStateMachine.Optimize();
			}
		}
		
        initial = underlyingStateMachine.getInitialState();
        
		underlyingStateMachine.setRandomSeed(1);
		LearningComponent.getCount = 0;
		underlyingStateMachine.performDepthCharge(initial,null);
		System.out.println("#post-learning gets for one depth charge: " + LearningComponent.getCount);
		
		System.gc();
		underlyingStateMachine.recreate(new RuntimeOptimizedComponentFactory());
		underlyingStateMachine.getInitialState();
		System.gc();
	}
	
	// This is the default State Machine
	public StateMachine getInitialStateMachine() {
		GamerLogger.setFileToDisplay("StateMachine");
		
		underlyingStateMachine = new TestPropnetStateMachine(new LearningComponentFactory());
		//ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
		//return new CachedStateMachine(underlyingStateMachine);
		return underlyingStateMachine;
	}
	/**
	 * This function is called at the start of each round
	 * You are required to return the Move your player will play 
	 * before the timeout.
	 * 
	 */
	public Move stateMachineSelectMove(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		// We get the current start time
		long start = System.currentTimeMillis();

		/**
		 * We put in memory the list of legal moves from the 
		 * current state. The goal of every stateMachineSelectMove()
		 * is to return one of these moves. The choice of which
		 * Move to play is the goal of GGP.
		 */
		List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
		
		int bestScore = -1;
		Move bestMove = null;
		
		int alpha = -1;
		int beta = 101;
		
		for(Move move : moves)
		{
			int score = minEval(getCurrentState(), move, alpha, beta);
			
			System.out.println("Move " + move + " scores: " + score);
			if ( score > bestScore )
			{
				bestScore = score;
				bestMove = move;
			}
		}

		// We get the end time
		// It is mandatory that stop<timeout
		long stop = System.currentTimeMillis();

		/**
		 * These are functions used by other parts of the GGP codebase
		 * You shouldn't worry about them, just make sure that you have
		 * moves, selection, stop and start defined in the same way as 
		 * this example, and copy-paste these two lines in your player
		 */
		notifyObservers(new GamerSelectedMoveEvent(moves, bestMove, stop - start));
		return bestMove;
	}

	private void flattenMoveSubLists(List<List<Move>> legalMoves, int iFromIndex, List<List<Move>> jointMoves, List<Move> partialJointMove)
	{
		if ( iFromIndex >= legalMoves.size())
		{
			jointMoves.add(new ArrayList<Move>(partialJointMove));
			return;
		}
		
		for(Move move : legalMoves.get(iFromIndex))
		{
			if (partialJointMove.size() <= iFromIndex)
			{
				partialJointMove.add(move);
			}
			else
			{
				partialJointMove.set(iFromIndex, move);
			}
			
			flattenMoveSubLists(legalMoves, iFromIndex+1, jointMoves, partialJointMove);
		}
	}

	private void flattenMoveLists(List<List<Move>> legalMoves, List<List<Move>> jointMoves)
	{
		List<Move> partialJointMove = new ArrayList<Move>();
		
		flattenMoveSubLists(legalMoves, 0, jointMoves, partialJointMove);
	}
	
	private int minEval(MachineState state, Move move, int alpha, int beta) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		List<List<Move>> legalMoves = new ArrayList<List<Move>>();
		
		for(Role role : getStateMachine().getRoles())
		{
			if ( !role.equals(getRole()) )
			{
				legalMoves.add(getStateMachine().getLegalMoves(state, role));
			}
			else
			{
				List<Move> myMoveList = new ArrayList<Move>();
				
				myMoveList.add(move);
				legalMoves.add(myMoveList);
			}
		}
		
		//	Now try all possible opponent moves and assume they will choose the joint worst
		List<List<Move>> jointMoves = new LinkedList<List<Move>>();
		
		flattenMoveLists(legalMoves, jointMoves);
		
		int worstScore = 1000;
		
		for(List<Move> jointMove : jointMoves)
		{
			MachineState newState = getStateMachine().getNextState(state, jointMove);
			int score = maxEval(newState, alpha, beta);
			
			if ( score < worstScore )
			{
				worstScore = score;
			}
			
			if ( score < beta )
			{
				beta = worstScore;
			}
			if ( worstScore <= alpha )
			{
				break;
			}
		}
		
		return worstScore;
	}
	
	private Map<MachineState, Integer> cachedMaxEvals = null;
	
	private int maxEval(MachineState state, int alpha, int beta) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		int result;
		
		if ( cachedMaxEvals == null )
		{
			cachedMaxEvals = new HashMap<MachineState,Integer>();
		}
		else if ( cachedMaxEvals.containsKey(state))
		{
			return cachedMaxEvals.get(state);
		}
		
		//System.out.println("MaxEval state: " + state);
		if ( getStateMachine().isTerminal(state))
		{
			result = getStateMachine().getGoal(state, getRole());
		}
		else
		{
			List<Move> moves = getStateMachine().getLegalMoves(state, getRole());
			
			int bestScore = -1;
			
			for(Move move : moves)
			{
				int score = minEval(state, move, alpha, beta);
				
				if ( score > bestScore )
				{
					bestScore = score;
					
					if ( score > alpha )
					{
						alpha = score;
					}
					if ( score >= beta )
					{
						break;
					}
				}
			}
			
			result = bestScore;
		}
		
		cachedMaxEvals.put(state, result);
		
		return result;
	}
	
	private Move stateMachineSelectMoveInternal(long timeout, boolean notify) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
	    StateMachine theMachine = getStateMachine();
		long start = System.currentTimeMillis();
		long finishBy = timeout - 1000;
		long numSamples = 0;
		
		ProfilerContext.resetStats();
	
		List<Move> moves = theMachine.getLegalMoves(getCurrentState(), getRole());
		Move selection = moves.get(0);
		if (moves.size() > 1) {		
			int[] moveTotalPoints = new int[moves.size()];
			int[] moveTotalAttempts = new int[moves.size()];
			
			// Perform depth charges for each candidate move, and keep track
			// of the total score and total attempts accumulated for each move.
			for (int i = 0; true; i = (i+1) % moves.size()) {
			    if (System.currentTimeMillis() > finishBy)
			        break;
			    
			    int theScore = performDepthChargeFromMove(getCurrentState(), moves.get(i));
			    moveTotalPoints[i] += theScore;
			    moveTotalAttempts[i] += 1;
			    
			    numSamples++;
			    
			    theMachine.updateRoot(getCurrentState());
			}
	
			// Compute the expected score for each move.
			double[] moveExpectedPoints = new double[moves.size()];
			for (int i = 0; i < moves.size(); i++) {
			    moveExpectedPoints[i] = (double)moveTotalPoints[i] / moveTotalAttempts[i];
			}
	
			// Find the move with the best expected score.
			int bestMove = 0;
			double bestMoveScore = moveExpectedPoints[0];
			for (int i = 1; i < moves.size(); i++) {
			    if (moveExpectedPoints[i] > bestMoveScore) {
			        bestMoveScore = moveExpectedPoints[i];
			        bestMove = i;
			    }
			}
			selection = moves.get(bestMove);
		}
	
		long stop = System.currentTimeMillis();
	
		GamerLogger.log("StateMachine", "Num MonteCarlo samples (test): " + numSamples);
		GamerLogger.log("StateMachine", "Stats: ");
		GamerLogger.log("StateMachine", underlyingStateMachine.getStats().toString());
		
		if ( ProfilerContext.getContext() != null )
		{
			GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
		}
		
		if ( notify )
		{
			notifyObservers(new GamerSelectedMoveEvent(moves, selection, stop - start));
		}
		
		return selection;
	}
	
	private int[] depth = new int[1];
	int performDepthChargeFromMove(MachineState theState, Move myMove) {	    
	    StateMachine theMachine = getStateMachine();
	    try {
	        MachineState finalState = theMachine.performDepthCharge(theMachine.getRandomNextState(theState, getRole(), myMove), depth);
	        return theMachine.getGoal(finalState, getRole());
	    } catch (Exception e) {
	        e.printStackTrace();
	        return 0;
	    }
	}
}
