package org.ggp.base.player.gamer.statemachine.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.profile.ProfilerSampleSetSimple;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;

public class Sancho extends SampleGamer {
	public Role ourRole;
	
    private Random r = new Random();
    private int numUniqueTreeNodes = 0;
    private int numTotalTreeNodes = 0;
    private int numFreedTreeNodes = 0;
    private int numNonTerminalRollouts = 0;
    private int numTerminalRollouts = 0;
    private int numUsedNodes = 0;
    private int numIncompleteNodes = 0;
    private int numCompletedBranches = 0;
    private int numWinningLinesSeen = 0;
    private int numLosingLinesSeen = 0;
    private boolean completeSelectionFromIncompleteParentWarned = false;
    
    private Map<ForwardDeadReckonInternalMachineState, TreeNode> positions = new HashMap<ForwardDeadReckonInternalMachineState,TreeNode>();

    private boolean useGoalHeuristic = false;
    private int rolloutSampleSize = 4;
    private int transpositionTableSize = 2000000;
    private final int maxOutstandingRolloutRequests = 4;
    private int numRolloutThreads = 4;
    private double explorationBias = 1.0;
    private TreeNode[] transpositionTable = null;
    private int nextSeq = 0;
    private List<TreeNode> freeList = new LinkedList<TreeNode>();
    private int largestUsedIndex = -1;
    private int sweepInstance = 0;
    private List<TreeNode> completedNodeQueue = new LinkedList<TreeNode>();
    private LinkedBlockingQueue<RolloutRequest>	queuedRollouts = new LinkedBlockingQueue<RolloutRequest>();
    private ConcurrentLinkedQueue<RolloutRequest>	completedRollouts = new ConcurrentLinkedQueue<RolloutRequest>();
    private int numQueuedRollouts = 0;
    private int numCompletedRollouts = 0;
    private RolloutProcessor[] rolloutProcessors = null;
    private Map<Move, MoveScoreInfo> cousinMoveCache = new HashMap<Move, MoveScoreInfo>();
    private TreeNodeRef cousinMovesCachedFor = null;
    
	private class RolloutProcessor implements Runnable
    {
    	private boolean stop = false;
    	private TestForwardDeadReckonPropnetStateMachine stateMachine;
    	private Thread runningThread = null;
    	
    	public RolloutProcessor(TestForwardDeadReckonPropnetStateMachine stateMachine)
    	{
    		this.stateMachine = stateMachine;
    	}
    	
    	public void disableGreedyRollouts()
    	{
    		stateMachine.disableGreedyRollouts();
    	}
    	
    	public void start()
    	{
    		if ( runningThread == null )
    		{
    			runningThread = new Thread(this);
    			runningThread.start();
    		}
    	}
    	
    	public void stop()
    	{
    		stop = true;
    		
    		if ( runningThread != null )
    		{
    			runningThread.interrupt();
    			runningThread = null;
    		}
    	}
    	
		@Override
		public void run()
		{
			try
			{
				while(!stop)
				{
					RolloutRequest request = queuedRollouts.take();
					
					try
					{
						request.process(stateMachine);
					}
					catch (TransitionDefinitionException e)
					{
						e.printStackTrace();
					}
					catch (MoveDefinitionException e)
					{
					e.printStackTrace();
					}
					catch (GoalDefinitionException e)
					{
						e.printStackTrace();
					}
				}
			}
	        catch (InterruptedException ie)
	        {
	             // This would be a surprise.
	        }
		}
    }
    
	private int highestRolloutScoreSeen = -100;
	
    private class RolloutRequest
    {
    	public TreeNodeRef								node;
    	public ForwardDeadReckonInternalMachineState	state;
    	public double[]									averageScore;
    	public double[]									averageSquaredScore;
    	public int										sampleSize;
	    public List<TreeNode>							path;
	    
	    public RolloutRequest()
	    {
	    	averageScore = new double[numRoles];
	    	averageSquaredScore = new double[numRoles];
	    }
	    
	    public  void process(TestForwardDeadReckonPropnetStateMachine stateMachine) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	    {
			ProfileSection methodSection = new ProfileSection("TreeNode.rollOut");
			try
			{
				for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
				{
					averageScore[roleIndex] = 0;
					averageSquaredScore[roleIndex] = 0;
				}
				
				for(int i = 0; i < sampleSize; i++)
				{
					//System.out.println("Perform rollout from state: " + state);
		        	numNonTerminalRollouts++;
		        	stateMachine.getDepthChargeResult(state, ourRole, null);
		        	
		        	for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
		        	{
		        		int score = stateMachine.getGoal(roleIndexToRole(roleIndex));
			        	averageScore[roleIndex] += score;
			        	averageSquaredScore[roleIndex] += score*score;
			        	
			        	if ( roleIndex == 0 && score > highestRolloutScoreSeen )
			        	{
			        		highestRolloutScoreSeen = score;
			        	}
		        	}
		        	//int score = netScore(stateMachine, null);
				}
				
				for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
				{
					averageScore[roleIndex] /= sampleSize;
					averageSquaredScore[roleIndex] /= sampleSize;
				}
				
				completedRollouts.add(this);
			}
			finally
			{
				methodSection.exitScope();
			}
	    }
    }
    
    private class TreeNodeRef
    {
    	public TreeNode node;
    	public int seq;
    	
    	public TreeNodeRef(TreeNode node)
    	{
    		this.node = node;
    		this.seq = node.seq;
    	}
    }
    
    private class MoveScoreInfo
    {
    	public double	averageScore = 0;
    	public int		sampleSize = 0;
    }
    
	private int netScore(TestForwardDeadReckonPropnetStateMachine stateMachine, ForwardDeadReckonInternalMachineState state) throws GoalDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("TreeNode.netScore");
		try
		{
        	int result = 0;
        	int bestEnemyScore = 0;
        	for(Role role : stateMachine.getRoles())
        	{
        		if ( !role.equals(ourRole) )
        		{
        			int score = stateMachine.getGoalNative(state, role);
        			if ( score > bestEnemyScore )
        			{
        				bestEnemyScore = score;
        			}
        		}
        		else
        		{
        			result = stateMachine.getGoalNative(state, role);
        		}
        	}
        	
        	int winBonus = 0;
        	if ( result >= bestEnemyScore )
        	{
        		winBonus += 5;
        		
        		if ( result > bestEnemyScore )
        		{
        			winBonus += 5;
        		}
        	}
        	int rawResult = (isPuzzle ? result : ((result + winBonus)*100)/110);
        	int normalizedResult = ((rawResult - MinRawNetScore)*100)/(MaxRawNetScore - MinRawNetScore);
        	
        	if ( normalizedResult > 100 && !overExpectedRangeScoreReported )
        	{
        		normalizedResult = 100;
        		overExpectedRangeScoreReported = true;
        		System.out.println("Saw score that nornmalized to > 100");
        	}
        	else if ( normalizedResult < 0 && !underExpectedRangeScoreReported)
        	{
        		normalizedResult = 0;
        		underExpectedRangeScoreReported = true;
        		System.out.println("Saw score that nornmalized to < 0");
        	}
        	
        	return normalizedResult;
		}
		finally
		{
			methodSection.exitScope();
		}
	}
    
	private double bestScoreSeen = -100;
	
    private void processCompletedRollouts(long timeout)
    {
    	//	Process nay outstanding node completions first, as their processing may
    	//	have been interrupted due to running out of time at the end of the previous
    	//	turn's processing
		processNodeCompletions(timeout);
		
    	while(!completedRollouts.isEmpty() && System.currentTimeMillis() < timeout)
    	{
    		RolloutRequest request = completedRollouts.remove();
    		TreeNode 	   node = request.node.node;
    		
    		if ( request.node.seq == node.seq && !node.complete )
    		{
		        node.updateStats(request.averageScore, request.averageSquaredScore, request.sampleSize, request.path, false);
	    		processNodeCompletions(timeout);
    		}
    		
    		numCompletedRollouts++;
    	}
    }
	
	private void processNodeCompletions(long timeout)
	{
		while(!completedNodeQueue.isEmpty() && System.currentTimeMillis() < timeout)
		{
			//validateAll();
			TreeNode node = completedNodeQueue.remove(0);
			
			if ( !node.freed )
			{
				node.processCompletion();
			}
		}
	}
  
	//	Array of roles reordered so our role is first
	private Role[] reorderedRoles = null;
    private Move[] canonicallyOrderedMoveBuffer = null;
    
	private void initializeRoleOrdering()
	{
		reorderedRoles = new Role[numRoles];
		canonicallyOrderedMoveBuffer = new Move[numRoles];
		
		reorderedRoles[0] = ourRole;
		
		int roleIndex = 1;
		for(Role role : underlyingStateMachine.getRoles())
		{
			if ( role.equals(ourRole))
			{
				continue;
			}
			
			reorderedRoles[roleIndex++] = role;
		}
	}
	
	private Role roleIndexToRole(int roleIndex)
	{
		return reorderedRoles[roleIndex];
	}
	
	private Move[] getMoveCanonicallyOrdered(Move[] move)
	{
		int index = 0;
		boolean processedOurMove = false;
		
		for(Role role : underlyingStateMachine.getRoles())
		{
			if ( role.equals(ourRole))
			{
				canonicallyOrderedMoveBuffer[index++] = move[0];
				processedOurMove = true;
			}
			else
			{
				canonicallyOrderedMoveBuffer[index] = (processedOurMove ? move[index] : move[index+1]);
				index++;
			}
		}
		
		return canonicallyOrderedMoveBuffer;
	}
	
	private TreeNode allocateNode(TestForwardDeadReckonPropnetStateMachine underlyingStateMachine, ForwardDeadReckonInternalMachineState state, TreeNode parent) throws GoalDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("allocateNode");
		try
		{
			TreeNode result = (state != null ? positions.get(state) : null);
			
			//validateAll();
			numTotalTreeNodes++;
			if ( result == null )
			{
				numUniqueTreeNodes++;
				
				//System.out.println("Add state " + state);
				if ( largestUsedIndex < transpositionTableSize-1 )
				{
					result = new TreeNode();
					transpositionTable[++largestUsedIndex] = result;
				}
				else if ( !freeList.isEmpty())
				{
					result = freeList.remove(0);
					
					if ( !result.freed )
					{
						System.out.println("Bad allocation choice");
					}
					
					result.reset(false);
				}
				else
				{
					throw new RuntimeException("Unexpectedly full transition table");
				}
				
				result.state = state;
				result.seq = nextSeq++;
				
				//if ( positions.values().contains(result))
				//{
				//	System.out.println("Node already referenced by a state!");
				//}
				if ( state != null )
				{
					positions.put(state, result);
				}
				
				numUsedNodes++;
			}
			else
			{
				if ( result.freed)
				{
					System.out.println("Bad ref in positions table!");
				}
				if ( result.decidingRoleIndex != numRoles-1 )
				{
					System.out.println("Non-null move in position cache");
				}
			}
			
			if ( parent != null )
			{
				result.parents.add(parent);
				
				parent.adjustDescendantCounts(result.descendantCount+1);
			}
			
			//validateAll();
			return result;
		}
		finally
		{
			methodSection.exitScope();
		}
	}
   
	private class TreeEdge
	{
		public TreeEdge()
		{
			jointPartialMove = new Move[numRoles];
		}
		
		int			numChildVisits = 0;
		TreeNodeRef	child;
		private Move[] jointPartialMove;
		boolean		hasCachedPatternMatchValue = false;
		double		cachedPatternMatchValue;
	}
	
	private class TreeNode
	{
	    static final double epsilon = 1e-6;

	    private int seq = -1;
		private int numVisits = 0;
		private double[] averageScores;
		private double[] averageSquaredScores;
		private ForwardDeadReckonInternalMachineState state;
		private int decidingRoleIndex;
		private boolean isTerminal = false;
		private TreeEdge[] children = null;
		private Set<TreeNode> parents = new HashSet<TreeNode>();
		private int trimmedChildren = 0;
		private int sweepSeq;
		//private TreeNode sweepParent = null;
		boolean freed = false;
		int descendantCount = 0;
	    private int leastLikelyWinner = -1;
	    private double leastLikelyRunnerUpValue;
	    private int mostLikelyWinner = -1;
	    private double mostLikelyRunnerUpValue;
	    private boolean complete = false;
		
		private TreeNode() throws GoalDefinitionException
		{
			averageScores = new double[numRoles];
			averageSquaredScores = new double[numRoles];
		}
		
		private void markComplete(double[] values)
		{
			if ( !complete )
			{
				//System.out.println("Mark complete  node seq: " + seq);
				//validateAll();
				adjustPropagatedContribution(values, averageScores, null, null, this, null);
				
				for(int i = 0; i < numRoles; i++)
				{
					averageScores[i] = values[i];
				}
				
				numCompletedBranches++;
				complete = true;
				
				//System.out.println("Mark complete with score " + averageScore + (ourMove == null ? " (for opponent)" : " (for us)") + " in state: " + state);
				if ( this == root )
				{
					System.out.println("Mark root complete");
				}
				else
				{
					//if ( parents.contains(root))
					//{
					//	System.out.println("First level move completed");
					//}
					completedNodeQueue.add(this);
				}
				
				if ( trimmedChildren > 0 )
				{
					//	Don't consider a complete node in the incomplete counts ever
					numIncompleteNodes--;
					if ( numIncompleteNodes < 0 )
					{
						System.out.println("Unexpected negative count of incomplete nodes");
					}
				}
				//validateAll();
			}
		}
		
		private void processCompletion()
		{
			//System.out.println("Process completion of node seq: " + seq);
			//validateAll();
			//	Children can all be freed, at least from this parentage
			if ( children != null )
			{
				int numDescendantsFreed = 0;
				
				for(TreeEdge edge : children)
				{
					TreeNodeRef cr = edge.child;
					if ( cr.node.seq == cr.seq )
					{
						numDescendantsFreed += cr.node.descendantCount + 1;
						cr.node.freeFromAncestor(this);
						
	            		trimmedChildren++;
	            		
	            		cr.seq = -1;
					}
					else if ( cr.seq != -1 )
					{
						cr.seq = -1;
	            		trimmedChildren++;
					}
				}
				
				if ( trimmedChildren == children.length )
				{
					children = null;
				}
				else
				{
					System.out.println("Impossible!");
				}
				
				//	Adjust descendant count
				adjustDescendantCounts(-numDescendantsFreed);
			}		
			
			boolean decidingRoleWin = false;
			boolean mutualWin = true;
			
			for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
			{
				if (roleIndex == decidingRoleIndex )
				{
					//	For multi-player games do not auto-complete opponent joint move wins
					if ( averageScores[roleIndex] > 99.5 )
					{
						if ((!isMultiPlayer && !isSimultaneousMove) || roleIndex == 0)
						{
							decidingRoleWin = true;
						}
					}
					else
					{
						mutualWin = false;
					}
				}
			}
			
			for(TreeNode parent : parents)
			{
				if ( decidingRoleWin && !mutualWin )
				{
					// Win for whoever just moved after they got to choose so parent node is also decided
					parent.markComplete(averageScores);
				}
				else
				{
					//	If all children are complete then the parent is - give it a chance to
					//	decide
					parent.checkChildCompletion();
				}
			}
			//validateAll();
		}
		
		private void freeFromAncestor(TreeNode ancestor)
		{
			//if ( sweepParent == ancestor && sweepSeq == sweepInstance)
			//{
			//	System.out.println("Removing sweep parent");
			//}
			parents.remove(ancestor);
			
			if ( parents.size() == 0 )
			{
				if ( children != null )
				{
					for(TreeEdge edge : children)
					{
						if ( edge.child.node.seq == edge.child.seq )
						{
							edge.child.node.freeFromAncestor(this);
						}
					}
				}
				
				freeNode();
			}
		}
		
		private void checkChildCompletion()
		{
			boolean allChildrenComplete = true;
			double bestValue = -1000;
			double[] bestValues = null;
			double[] averageValues = new double[numRoles];
			int roleIndex = (decidingRoleIndex+1)%numRoles;
			boolean decidingRoleWin = false;
			
			for(TreeEdge edge : children)
			{
				TreeNodeRef cr = edge.child;
				if ( cr.node.seq == cr.seq )
				{
					if ( !cr.node.complete )
					{
						allChildrenComplete = false;
					}
					else if ( cr.node.averageScores[roleIndex] > bestValue )
					{
						bestValue = cr.node.averageScores[roleIndex];
						bestValues = cr.node.averageScores;
						
						if ( bestValue > 99.5 )
						{
							//	Win for deciding role which they will choose unless it is also
							//	a mutual win
							boolean mutualWin = true;
							
							for(int i = 0; i < numRoles; i++)
							{
								if ( cr.node.averageScores[i] < 99.5 )
								{
									mutualWin = false;
								}
							}
							decidingRoleWin |= !mutualWin;
						}
					}
					
					for(int i = 0; i < numRoles; i++)
					{
						averageValues[i] += cr.node.averageScores[i];
					}
				}
				else
				{
					allChildrenComplete = false;
				}
			}
			
			if ( allChildrenComplete || (decidingRoleWin && ((!isMultiPlayer && !isSimultaneousMove) || roleIndex == 0)) )
			{
				//	Opponent's choice which child to take, so take their
				//	best value and crystalize as our value.   However, if it's simultaneous
				//	move complete with the average score since
				//	opponents cannot make the pessimal (for us) choice reliably
				if (isSimultaneousMove && roleIndex > 0)
				{				
					for(int i = 0; i < numRoles; i++)
					{
						averageValues[i] /= children.length;
					}
					
					markComplete(averageValues);
				}
				else
				{
					markComplete(bestValues);
				}
			}
		}
		
		public void reset(boolean freed)
		{
			numVisits = 0;
			if ( averageScores != null )
			{
				if ( freed )
				{
					averageScores = null;
					averageSquaredScores = null;
				}
				else
				{
					for(int i = 0; i < averageScores.length; i++)
					{
						averageScores[i] = 0;
						averageSquaredScores[i] = 0;
					}
				}
			}
			else if ( !freed )
			{
				averageScores = new double[numRoles];
				averageSquaredScores = new double[numRoles];
			}
			state = null;
			isTerminal = false;
			children = null;
			parents.clear();
			trimmedChildren = 0;
			this.freed = freed;
			descendantCount = 0;
			leastLikelyWinner = -1;
			mostLikelyWinner = -1;
			complete = false;
		}
		
		private TreeNodeRef getRef()
		{
			return new TreeNodeRef(this);
		}
		
	    public void adjustDescendantCounts(int adjustment)
	    {
	    	//	Slight hack - assume puzzles will be small enough that we won't have a strong need
	    	//	to trim.  This helps games that are intensely transpositional (not limited to puzzles but
	    	//	prevalent there), because the combination of paths to a node in such a game can be large
	    	//	which makes updating the descendant counts prohibitively expensive.
	    	//	TODO - this need a more generic solution 
	    	if ( !isPuzzle )
	    	{
		    	if ( freed )
		    	{
		    		System.out.println("Manipulating deleted node");
		    	}
				for(TreeNode parent : parents)
				{
					parent.adjustDescendantCounts(adjustment);
				}
	    	}
			
			descendantCount += adjustment;
	    }
		
		private int validate()
		{
			int descendants = 0;
			
			if ( children != null )
			{
				int missingChildren = 0;
				for(TreeEdge edge : children)
				{
					if ( edge != null )
					{
						TreeNodeRef cr = edge.child;
						if ( cr.node.seq == cr.seq )
						{
							if ( !cr.node.parents.contains(this))
							{
								System.out.println("Missng parent link");
							}
							if ( cr.node.complete && cr.node.averageScores[decidingRoleIndex] > 99.5 && !complete && !completedNodeQueue.contains(cr.node) )
							{
								System.out.println("Completeness constraint violation");
							}
							if ( (cr.node.decidingRoleIndex) == decidingRoleIndex && !isPuzzle )
							{
								System.out.println("Descendant type error");
							}
							descendants += cr.node.validate();
						}
						else
						{
							missingChildren++;
						}
					}
				}
				
				if ( missingChildren != trimmedChildren )
				{
					System.out.println("Trimmed child count incorrect");
				}
			}
			
			if ( descendants != descendantCount )
			{
				System.out.println("Descendant count mismatch");
			}
			
			return descendants+1;
		}
		
		private void markTreeForSweep()
		{
			if ( sweepSeq != sweepInstance )
			{
				sweepSeq = sweepInstance;
				if( children != null )
				{
					for(TreeEdge edge : children)
					{
						if ( edge.child.node.seq == edge.child.seq )
						{
							//if ( !cr.node.parents.contains(this))
							//{
							//	System.out.println("Child relation inverse missing");
							//}
							//cr.node.sweepParent = this;
							edge.child.node.markTreeForSweep();
						}
					}
				}
			}
		}
		
		private void freeNode()
		{
			ProfileSection methodSection = new ProfileSection("TreeNode.freeNode");
			try
			{
				//validateAll();
			
				if ( freed )
				{
					System.out.println("Freeing already free node!");
				}
				if ( decidingRoleIndex == numRoles-1 )
				{
					//if ( positions.get(state) != this )
					//{
					//	System.out.println("Position index does not point to freed node");
					//}
					positions.remove(state);
				}
				//if ( positions.containsValue(this))
				//{
				//	System.out.println("Node still referenced!");
				//}
				
				if ( trimmedChildren > 0 && !complete )
				{
					numIncompleteNodes--;
					if ( numIncompleteNodes < 0 )
					{
						System.out.println("Unexpected negative count of incomplete nodes");
					}
				}
				if ( complete )
				{
					numCompletedBranches--;
				}
				
				if ( children != null )
				{
					for(TreeEdge edge : children)
					{
						if ( edge != null )
						{
							if ( edge.child.node.seq == edge.child.seq )
							{
								if ( edge.child.node.parents.size() != 0)
								{
									int numRemainingParents = edge.child.node.parents.size();
									//if ( cr.node.sweepParent == this && sweepSeq == sweepInstance)
									//{
									//	System.out.println("Removing sweep parent");
									//}
									edge.child.node.parents.remove(this);
									if ( numRemainingParents == 0)
									{
										System.out.println("Orphaned child node");
									}
									else
									{
										//	Best estimate of likely paths to the child node given removal of parent
										edge.child.node.numVisits = (edge.child.node.numVisits*numRemainingParents + numRemainingParents)/(numRemainingParents+1);
									}
								}
							}
						}
					}
				}
				
				//System.out.println("    Freeing (" + ourIndex + "): " + state);
				numFreedTreeNodes++;
				seq = -2;	//	Must be negative and distinct from -1, the null ref seq value
				freeList.add(this);
				freed = true;
				
				numUsedNodes--;
				//validateAll();
			}
			finally
			{
				methodSection.exitScope();
			}
		}
		
		public void freeAllBut(TreeNode descendant)
		{
			if ( descendant != null )
			{
				System.out.println("Free all but rooted in state: " + descendant.state);
				sweepInstance++;
				
				descendant.markTreeForSweep();
				descendant.parents.clear();	//	Do this here to allow generic orphan checking in node freeing
											//	without tripping over this special case
			}
			
			if ( descendant == this || sweepSeq == sweepInstance)
			{
				//System.out.println("    Leaving: " + state);
				return;
			}
			
			if ( children != null )
			{
				for(TreeEdge edge : children)
				{
					if ( edge.child.node.seq == edge.child.seq )
					{
						edge.child.node.freeAllBut(null);
					}
				}
			}
			
			freeNode();
		}
		
		public TreeNode findNode(ForwardDeadReckonInternalMachineState targetState, int maxDepth)
		{
			if ( state.equals(targetState) && decidingRoleIndex == numRoles-1 )
			{
				return this;
			}
			else if ( maxDepth == 0 )
			{
				return null;
			}
			
			if ( children != null )
			{
				for(TreeEdge edge : children)
				{
					TreeNodeRef cr = edge.child;
					if( cr.node.seq == cr.seq )
					{
						TreeNode childResult = cr.node.findNode(targetState, maxDepth-1);
						if ( childResult != null )
						{
							return childResult;
						}
					}
				}
			}
			
			return null;
		}
		
		public void disposeLeastLikelyNode()
		{
			ProfileSection methodSection = new ProfileSection("TreeNode.disposeLeastLikelyNode");
			try
			{
				TreeNode leastLikely = selectLeastLikelyNode(null, 0);
				
				leastLikely.adjustDescendantCounts(-1);
				leastLikely.freeNode();
				//validateAll();
			}
			finally
			{
				methodSection.exitScope();
			}
		}
		
		public TreeNode selectLeastLikelyNode(TreeEdge from, int depth)
		{
	        int selectedIndex = -1;
	        double bestValue = -Double.MAX_VALUE;
	        
    		//	Find the role this node is choosing for
    		int roleIndex = (decidingRoleIndex+1)%numRoles;
	        
			cousinMovesCachedFor = null;
        
			//validateAll();
	        //System.out.println("Select LEAST in " + state);
	        if ( freed )
	        {
	        	System.out.println("Encountered freed node in tree walk");
	        }
	        if ( children != null )
	        {
	        	if ( children.length == 1 )
	        	{
	        		TreeEdge edge = children[0];
	        		
	        		if ( edge.child.node.seq == edge.child.seq )
	        		{
	        			selectedIndex = 0;
	        		}
	        	}
	        	else
	        	{
		        	if ( leastLikelyWinner != -1 )
		        	{
		        		TreeEdge edge = children[leastLikelyWinner];
		        		TreeNode c = edge.child.node;
		        		if ( edge.child.seq == c.seq )
		        		{
				            double uctValue;
				            if ( children[leastLikelyWinner].numChildVisits == 0 )
				            {
				            	uctValue = -1000;
				            }
				            else
				            {
				            	uctValue = -explorationUCT(children[leastLikelyWinner].numChildVisits, roleIndex) - exploitationUCT(children[leastLikelyWinner], roleIndex);
				            	//uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[leastLikelyWinner])+1) / numChildVisits[leastLikelyWinner]);
				            }
				            uctValue /= Math.log(c.descendantCount+2);	//	utcVal is negative so this makes larger subtrees score higher (less negative)
		        			
				            if ( uctValue >= leastLikelyRunnerUpValue )
				            {
				            	selectedIndex = leastLikelyWinner;
				            }
		        		}
		        	}
		        	
		        	if ( selectedIndex == -1 )
		        	{
			        	leastLikelyRunnerUpValue = -Double.MAX_VALUE;
				        for (int i = 0; i < children.length; i++)
				        {
				        	TreeEdge edge = children[i];
				        	TreeNodeRef cr = edge.child;
				        	if ( cr != null )
				        	{
					            TreeNode c = cr.node;
					            if ( c.seq != cr.seq )
					            {
					            	if ( cr.seq != -1 )
					            	{
					            		if ( trimmedChildren++ == 0)
					            		{
					            			numIncompleteNodes++;
					            		}
					            		cr.seq = -1;
					            	}
					            }
					            else
					            {
					    	        if ( c.freed )
					    	        {
					    	        	System.out.println("Encountered freed child node in tree walk");
					    	        }
						            double uctValue;
						            if ( children[i].numChildVisits == 0 )
						            {
						            	uctValue = -1000;
						            }
						            //else if ( c.complete )
						            //{
						            	//	Resist clearing away complete nodes as they potentially
						            	//	represent a lot of work
						            //	uctValue = -500;
						            //}
						            else
						            {
						            	uctValue = -explorationUCT(children[i].numChildVisits, roleIndex) - exploitationUCT(children[i], roleIndex);
						            	//uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[i])+1) / numChildVisits[i]);
						            }
						            uctValue /= Math.log(c.descendantCount+2);	//	utcVal is negative so this makes larger subtrees score higher (less negative)
						            
						            //if ( c.isLeaf() )
						            //{
						            //	uctValue += uctValue/(depth+1);
						            //}
						                          
						            //System.out.println("  child score of " + uctValue + " in state "+ c.state);
						            if (uctValue > bestValue)
						            {
						            	selectedIndex = i;
						            	if ( bestValue != -Double.MAX_VALUE )
						            	{
						            		leastLikelyRunnerUpValue = bestValue;
						            	}
						                bestValue = uctValue;
						            }
					            }
					        }
			        	}
			        }
		        }
	        }

			//validateAll();
	        if ( selectedIndex != -1 )
	        {
	        	leastLikelyWinner = selectedIndex;
		        //System.out.println("  selected: " + selected.state);
	        	return children[selectedIndex].child.node.selectLeastLikelyNode(children[selectedIndex], depth+1);
	        }
	        
	        if ( descendantCount > 0 && !isPuzzle )
	        {
	        	System.out.println("Selecting non-leaf for removal!");
	        }
	        
	        if ( depth < 2 )
	        	System.out.println("Selected unlikely node at depth " + depth);
	        return this;
		}
		
	    public void selectAction(long timeout) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
			ProfileSection methodSection = new ProfileSection("TreeNode.selectAction");
			try
			{
				//validateAll();
				completedNodeQueue.clear();
				
		        List<TreeNode> visited = new LinkedList<TreeNode>();
		        TreeNode cur = this;
		        TreeEdge from = null;
		        visited.add(this);
		        while (!cur.isUnexpanded()) {
		            from = cur.select(from);
		            cur = from.child.node;
		            visited.add(cur);
		        }
		        
		        TreeNode newNode;
		        if ( !cur.complete )
		        {
		        	//	Expand for each role so we're back to our-move as we always rollout after joint moves
			        cur.expand(from);
			        
			        if ( !cur.complete )
			        {
				        from = cur.select(from);
				        newNode = from.child.node;
				        visited.add(newNode);
				        while ( newNode.decidingRoleIndex != numRoles-1 && !newNode.complete)
				        {
				        	newNode.expand(from);
				        	if ( !newNode.complete )
				        	{
				        		from = newNode.select(from);
				        		newNode = from.child.node;
				        		visited.add(newNode);
				        	}
				        }
			        }
			        else
			        {
			        	newNode = cur;
			        }
		        }
		        else
		        {
		        	//	If we've selected a terminal node we still do a pseudo-rollout
		        	//	from it so its value gets a weight increase via back propagation
		        	newNode = cur;
		        }
				//validateAll();
		        //System.out.println("Rollout from: " + newNode.state);
		        RolloutRequest rollout = newNode.rollOut(visited);
		        if ( rollout != null )
		        {
		        	newNode.updateStats(rollout.averageScore, rollout.averageSquaredScore, rolloutSampleSize, visited, true);
		    		processNodeCompletions(timeout);
		        }
		        else
		        {
		        	newNode.updateVisitCounts(rolloutSampleSize, visited);
		        }
				//validateAll();
			}
			finally
			{
				methodSection.exitScope();
			}
	    }

	    public void expand(TreeEdge from) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
			ProfileSection methodSection = new ProfileSection("TreeNode.expand");
			try
			{
		    	if ( children == null || trimmedChildren > 0 )
		    	{
		    		//	Find the role this node is choosing for
		    		int roleIndex = (decidingRoleIndex+1)%numRoles;
		    		Role choosingRole = roleIndexToRole(roleIndex);
		    		//validateAll();
		    		
		    		//System.out.println("Expand our moves from state: " + state);
		    		List<Move> moves = underlyingStateMachine.getLegalMoves(state, choosingRole);

		    		TreeEdge[] newChildren = new TreeEdge[moves.size()];
		    		
	    			int index = 0;
		    		if ( children != null )
		    		{
		    			for(TreeEdge edge : children)
		    			{
		    				TreeNode child = edge.child.node;
		    				if ( edge.child.seq == child.seq )
		    				{
		    					moves.remove(edge.jointPartialMove[roleIndex]);
		    					newChildren[index] = edge;
			    				index++;
		    				}
		    			}
		    		}
	    			while(index < newChildren.length)
	    			{
	    				TreeEdge newEdge = new TreeEdge();
	    				ForwardDeadReckonInternalMachineState newState = null;
	    				
	    				for(int i = 0; i < roleIndex; i++ )
	    				{
	    					newEdge.jointPartialMove[i] = from.jointPartialMove[i];
	    				}
	    				newEdge.jointPartialMove[roleIndex] = moves.remove(0);
	    				if ( roleIndex == numRoles-1 )
	    				{
			    			newState = underlyingStateMachine.getNextState(state, getMoveCanonicallyOrdered(newEdge.jointPartialMove));
	    				}
	    				
    					newChildren[index] = newEdge;
    					newEdge.child = allocateNode(underlyingStateMachine, newState, this).getRef();
    					
    					TreeNode newChild = newEdge.child.node;
    					
    					newChild.decidingRoleIndex = roleIndex;
    					
    					if ( newState == null )
    					{
    						newChild.state = state;
    					}
    					else
    					{
    						if ( underlyingStateMachine.isTerminal(newState) )
    						{
    							newChild.isTerminal = true;
    							
    							for(int i = 0; i < numRoles; i++)
    							{
    								newChild.averageScores[i] = underlyingStateMachine.getGoal(roleIndexToRole(i));
    								newChild.averageSquaredScores[i] = 0;
    							}
    						}
    					}
    					index++;
	    			}
	    			
	    			children = newChildren;
		    		//validateAll();
		    	
	        		if ( trimmedChildren > 0 )
	        		{
	        			trimmedChildren = 0;	//	This is a fresh expansion entirely can go back to full UCT
	        			numIncompleteNodes--;
						if ( numIncompleteNodes < 0 )
						{
							System.out.println("Unexpected negative count of incomplete nodes");
						}
	        		}
			    	
			    	boolean completeChildFound = false;
			    	
					for(TreeEdge edge : children)
					{
						TreeNodeRef cr = edge.child;
						if ( cr.node.seq == cr.seq )
						{
							if ( cr.node.isTerminal )
							{
								cr.node.markComplete(cr.node.averageScores);
								completeChildFound = true;
							}
							if ( cr.node.complete )
							{
								completeChildFound = true;
							}
						}
					}
					
					if ( completeChildFound && !complete )
					{
						checkChildCompletion();
					}
		    		//validateAll();
		    	}
			}
			finally
			{
				methodSection.exitScope();
			}
	    }
	    
	    private double heuristicValue(TreeEdge edge)
	    {
	    	double result = 0;
	    	
    		//	TODO - for non-puzzle this needs to see the state that will result from the decision
    		//	choice which requires a full joint move - could b problematic for simultaneous play
    		//	games (and will need slight modification to node expansion for non-simultaneous to ensure
    		//	expansion to next state)
    		if ( edge.hasCachedPatternMatchValue )
    		{
    			result += edge.cachedPatternMatchValue;
    		}
    		else
    		{
    			edge.cachedPatternMatchValue = patternMatchValue(edge.child.node.state);
    			edge.hasCachedPatternMatchValue = true;
    			result += edge.cachedPatternMatchValue;
    		}
	    	
	    	return result*2/Math.log(edge.numChildVisits+2);
	    }
	    
	    private double explorationUCT(int numChildVisits, int roleIndex)
	    {
	    	//	When we propagate adjustments due to completion we do not also adjust the variance contribution
	    	//	so this can result in 'impossibly' low (aka negative) variance - take a lower bound of 0
        	double varianceBound = Math.max(0, averageSquaredScores[roleIndex] - averageScores[roleIndex]*averageScores[roleIndex])/10000 + Math.sqrt(2*Math.log(Math.max(numVisits,numChildVisits)+1) / numChildVisits);
        	return explorationBias*Math.sqrt(2*Math.min(0.5,varianceBound)*Math.log(Math.max(numVisits,numChildVisits)+1) / numChildVisits);
	    }
	    
	    private double getAverageCousinMoveValue(TreeEdge relativeTo, int roleIndex)
	    {
	    	if ( relativeTo.child.node.decidingRoleIndex == 0 )
	    	{
	    		return relativeTo.child.node.averageScores[roleIndex]/100;
	    	}
	    	else if ( cousinMovesCachedFor == null || cousinMovesCachedFor.node != this || cousinMovesCachedFor.seq != seq )
	    	{
	    		cousinMovesCachedFor = getRef();
	    		cousinMoveCache.clear();
	    		
	    		for(TreeNode parent : parents)
	    		{
	    			for(TreeEdge edge : parent.children)
	    			{
	    				TreeNode child = edge.child.node;
	    				
	    				if ( edge.child.seq == child.seq && child.children != null )
	    				{
	    	    			for(TreeEdge nephewEdge : child.children)
	    	    			{
	    	    				TreeNode nephew = nephewEdge.child.node;
	    	    				
	    	    				if ( nephewEdge.child.seq == nephew.seq )
	    	    				{
	    	    					Move move = nephewEdge.jointPartialMove[relativeTo.child.node.decidingRoleIndex];
			    					MoveScoreInfo accumulatedMoveInfo = cousinMoveCache.get(move);
			    					if ( accumulatedMoveInfo == null )
			    					{
			    						accumulatedMoveInfo = new MoveScoreInfo();
			    						cousinMoveCache.put(move, accumulatedMoveInfo);
			    					}
			    					
			    					accumulatedMoveInfo.averageScore = (accumulatedMoveInfo.averageScore*accumulatedMoveInfo.sampleSize + nephew.averageScores[roleIndex])/(++accumulatedMoveInfo.sampleSize);
	    	    				}
	    	    			}
	    				}
	    			}
	    		}
	    	}
	    	
			MoveScoreInfo accumulatedMoveInfo = cousinMoveCache.get(relativeTo.jointPartialMove[relativeTo.child.node.decidingRoleIndex]);
			if ( accumulatedMoveInfo == null )
			{
				System.out.println("No newphews found for search move including own child!");
				cousinMovesCachedFor = null;
				//getAverageCousinMoveValue(relativeTo);
				return relativeTo.child.node.averageScores[roleIndex]/100;
			}
			else
			{
				return accumulatedMoveInfo.averageScore/100;
			}
	    }
	    
	    private double exploitationUCT(TreeEdge inboundEdge, int roleIndex)
	    {
	    	//double stdDeviationMeasure = Math.sqrt((averageSquaredScore - averageScore*averageScore)/10000) - 0.25;
	    	//double stdDeviationContribution = stdDeviationMeasure - 2*averageScore*stdDeviationMeasure/100;
	    	//final double alpha = 0.5;
	    	
	    	//if ( isMultiPlayer && ourMove == null )
	    	//{
	    		//	For multi-player games inject noise onto enemy scores of magnitude proportional
	    		//	to their observed non-correlation of terminal position scores
	    	//	return Math.min(0,  Math.max(averageScore + r.nextInt(multiRoleAverageScoreDiff*2) - multiRoleAverageScoreDiff,100))/100;
	    	//}
	    	//else
	    	if ( isSimultaneousMove )
	    	{
	    		return getAverageCousinMoveValue(inboundEdge, roleIndex);
	    	}
	    	else if ( useGoalHeuristic )
	    	{
     	    	int roleScore;
				try {
					roleScore = netScore(underlyingStateMachine, inboundEdge.child.node.state);
				} catch (GoalDefinitionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					roleScore = 0;
				}

     	    	return inboundEdge.child.node.averageScores[roleIndex]/100 + roleScore/(100*Math.log(inboundEdge.child.node.numVisits+2));// + averageSquaredScore/20000;
	    	}
	    	else
	    	{
	    		return inboundEdge.child.node.averageScores[roleIndex]/100;// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;
	    	}
	    }

	    private TreeEdge select(TreeEdge from) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
	        TreeEdge selected = null;
	        int selectedIndex = -1;
	        
    		//	Find the role this node is choosing for
    		int roleIndex = (decidingRoleIndex+1)%numRoles;
    		
			cousinMovesCachedFor = null;
	        //System.out.println("Select in " + state);
	        if ( trimmedChildren == 0 )
	        {
		        double bestValue = Double.MIN_VALUE;
		        
		        if ( children != null )
		        {
		        	if ( children.length == 1 )
		        	{
		        		TreeEdge edge = children[0];
		        		TreeNodeRef cr = edge.child;
		        		
		        		if ( cr.node.seq == cr.seq )
		        		{
		        			selectedIndex = 0;
		        		}
		        		else
		        		{
		        			trimmedChildren = 1;
		        			numIncompleteNodes++;
		        		}
		        	}
		        	else
		        	{
			        	if ( mostLikelyWinner != -1 )
			        	{
			        		TreeNodeRef cr = children[mostLikelyWinner].child;
			        		TreeNode c = cr.node;
			        		if ( cr.seq == c.seq && (!c.complete || ((isMultiPlayer || isSimultaneousMove) && decidingRoleIndex == 0)))
			        		{
					            double uctValue;
					            
					            if ( children[mostLikelyWinner].numChildVisits == 0 )
					            {
						            // small random number to break ties randomly in unexpanded nodes
					            	uctValue = 1000 +  r.nextDouble() * epsilon + heuristicValue(children[mostLikelyWinner]);
					            }
					            else
					            {
					            	uctValue = explorationUCT(children[mostLikelyWinner].numChildVisits, roleIndex) + exploitationUCT(children[mostLikelyWinner], roleIndex) + heuristicValue(children[mostLikelyWinner]);
					            	//uctValue = c.averageScore/100 + Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[mostLikelyWinner])+1) / numChildVisits[mostLikelyWinner]);
					            }
			        			
					            if ( uctValue >= mostLikelyRunnerUpValue )
					            {
					            	selectedIndex = mostLikelyWinner;
					            }
			        		}
			        	}
			        	
			        	if ( selectedIndex == -1 )
			        	{
				        	mostLikelyRunnerUpValue = Double.MIN_VALUE;
					        for (int i = 0; i < children.length; i++)
					        {
					        	TreeNodeRef cr = children[i].child;
					        	if ( cr != null )
					        	{
						            TreeNode c = cr.node;
						            if ( c.seq != cr.seq )
						            {
						            	if ( cr.seq != -1 )
						            	{
						            		if ( trimmedChildren++ == 0)
						            		{
						            			numIncompleteNodes++;
						            		}
						            		cr.seq = -1;
						            	}
						            	
						            	selectedIndex = -1;
						            	break;
						            }
						            else if ( !c.complete || ((isMultiPlayer || isSimultaneousMove) && decidingRoleIndex == 0) )
						            {
							            double uctValue;
							            if ( children[i].numChildVisits == 0 )
							            {
								            // small random number to break ties randomly in unexpanded nodes
							            	uctValue = 1000 +  r.nextDouble() * epsilon + heuristicValue(children[i]);
							            }
							            else
							            {
							            	uctValue = explorationUCT(children[i].numChildVisits, roleIndex) + exploitationUCT(children[i], roleIndex) + heuristicValue(children[i]);
							            	//uctValue = c.averageScore/100 + Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[i])+1) / numChildVisits[i]);
							            }

							            if (uctValue > bestValue)
							            {
							            	selectedIndex = i;
							            	if ( bestValue != Double.MIN_VALUE )
							            	{
							            		mostLikelyRunnerUpValue = bestValue;
							            	}
							                bestValue = uctValue;
							            }
						            }
						        }
				        	}
				        }
			        }
		        }
	        }
	        if ( selectedIndex == -1 )
	        {
	        	if ( children == null )
	        	{
	        		System.out.println("select on an unexpanded node!");
	        	}
	        	if ( trimmedChildren == 0 )
	        	{
	        		System.out.println("no selection found on untrimmed node!");
	        		//select();
	        		System.out.println("Terminality: " + underlyingStateMachine.isTerminal(state));
	        	}
	        	//System.out.println("  select random");
	        	//	pick at random.  If we pick one that has been trimmed re-expand it
	        	//	FUTURE - can establish a bound on the trimmed UCT value to avoid
	        	//	randomization for a while at least
	        	int childIndex = r.nextInt(children.length);
	        	selected = children[childIndex];
	        	TreeNodeRef cr = selected.child;

	        	if ( cr.seq != cr.node.seq )
	        	{
	        		expand(from);
	        		selected = children[childIndex];
		        	
		        	if ( selected.child.node.freed )
		        	{
		        		System.out.println("Selected freed node!");
		        	}
		        	if ( selected.child.node.complete && !isMultiPlayer && !isPuzzle )
		        	{
		        		if ( !completeSelectionFromIncompleteParentWarned )
		        		{
		        			completeSelectionFromIncompleteParentWarned = true;
			        		System.out.println("Selected complete node from incomplete parent");
		        		}
		        	}
	        	}
	        }
	        else
	        {
	        	mostLikelyWinner = selectedIndex;
	        	selected = children[selectedIndex];
	        	
	        	if ( selected.child.node.freed )
	        	{
	        		System.out.println("Selected freed node!");
	        	}
	        }
	        
	        //System.out.println("  selected: " + selected.state);
	        return selected;
	    }

	    public boolean isUnexpanded() {
	        return children == null || complete;
	    }
	    
	    private double scoreForMostLikelyResponseRecursive(TreeNode from, int forRoleIndex)
	    {
	    	//	Stop recursion at the next choice
	    	if ( children == null || complete )
	    	{
	    		return averageScores[forRoleIndex];
	    	}
	    	else if ( (decidingRoleIndex+1)%numRoles == forRoleIndex && from != null && children.length > 1 )
	    	{
	    		return from.averageScores[forRoleIndex];
	    	}
	    	
	    	double result = 0;
	    	double childResult = -Double.MAX_VALUE;
	    	
	    	for(TreeEdge edge : children)
	    	{
	    		if ( edge.child.seq == edge.child.node.seq )
	    		{
	    			double childVal = edge.child.node.averageScores[edge.child.node.decidingRoleIndex];
	    			
	    			if ( childVal > childResult )
	    			{
	    				childResult = childVal;
		    			result = edge.child.node.scoreForMostLikelyResponseRecursive(this, forRoleIndex);
	    			}
	    		}
	    	}
	    	
	    	return (childResult == -Double.MAX_VALUE ? averageScores[forRoleIndex] : result);
	    }
	    
	    private double scoreForMostLikelyResponse()
	    {
	    	return scoreForMostLikelyResponseRecursive(null, decidingRoleIndex);
	    }
	    
	    private String stringizeScoreVector()
	    {
	    	StringBuilder sb = new StringBuilder();
	    	
	    	sb.append("[");
	    	for(int i = 0; i < numRoles; i++)
	    	{
	    		if ( i > 0 )
	    		{
	    			sb.append(", ");
	    		}
	    		sb.append(averageScores[i]);
	    	}
	    	sb.append("]");
	    	if ( complete )
	    	{
	    		sb.append(" (complete)");
	    	}
	    	
	    	return sb.toString();
	    }
	    
	    private void traceFirstChoiceNode()
	    {
	    	if ( children == null )
	    	{
				System.out.println("    No choice response scores " + stringizeScoreVector());
	    	}
	    	else if ( children.length > 1 )
	    	{
				for(TreeEdge edge2 : children)
				{
					if ( edge2.child.seq == edge2.child.node.seq )
					{
						System.out.println("    Response " + edge2.jointPartialMove[edge2.child.node.decidingRoleIndex] + " scores " + edge2.child.node.stringizeScoreVector());
					}
				}    		
	    	}
	    	else
	    	{
	    		children[0].child.node.traceFirstChoiceNode();
	    	}
	    }
	    
	    public Move getBestMove(boolean traceResponses)
	    {
	    	double bestScore = -Double.MAX_VALUE;
	    	double bestRawScore = -Double.MAX_VALUE;
	    	Move rawResult = null;
	    	Move result = null;
	    	
	    	for(TreeEdge edge : children)
	    	{
	    		TreeNode child = edge.child.node;
	    		
	    		Double moveScore = isSimultaneousMove ? child.averageScores[0] : child.scoreForMostLikelyResponse();
	    		if ( moveScore < 0.5 && edge.child.node.complete )
	    		{
	    			//	If everything loses with perfect play go for the highest variance and make
	    			//	the opponent work for it!
	    			moveScore = child.averageSquaredScores[0]/100 - 100;
	    		}
	    		System.out.println("Move " + edge.jointPartialMove[0] + " scores " + moveScore + " (raw score " + child.averageScores[0] + ", selection count " + child.numVisits + (child.complete ? ", complete" : "") + ")");
    			if (child.children != null && !child.complete && traceResponses)
    			{
    				child.traceFirstChoiceNode();
    			}
	    		if ( moveScore > bestScore || (moveScore == bestScore && child.complete && moveScore > 0))
	    		{
	    			bestScore = moveScore;
	    			result = edge.jointPartialMove[0];
	    		}
	    		if ( child.averageScores[0] > bestRawScore || (child.averageScores[0] == bestRawScore && child.complete && child.averageScores[0] > 0))
	    		{
	    			bestRawScore = child.averageScores[0];
	    			rawResult = edge.jointPartialMove[0];
	    		}
	    	}
	    	
	    	if ( result == null )
	    	{
	    		System.out.println("No move found!");
	    	}
	    	if ( rawResult != result )
	    	{
	    		System.out.println("1 level minimax result differend from best raw move: " + rawResult);
	    	}
	    	return result;
	    }

	    public RolloutRequest rollOut(List<TreeNode> path) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	    {
        	RolloutRequest request = new RolloutRequest();
        	
	        if ( complete )
	        {
	        	//System.out.println("Terminal state " + state + " score " + averageScore);
	        	numTerminalRollouts++;
	        	
	        	request.averageScore = averageScores;
	        	request.averageSquaredScore = averageSquaredScores;
	        	
	        	return request;
	        }
	        else
	        {
	        	if ( decidingRoleIndex != numRoles-1 )
	        	{
	        		System.out.println("Unexpected rollout state");
	        	}
	        	
	        	request.state = state;
	        	request.node = getRef();
	        	request.sampleSize = rolloutSampleSize;
	        	request.path = path;
	        	
	        	numQueuedRollouts++;
	        	queuedRollouts.add(request);
	        	
	        	return null;
	        }
	    }
		
		private void adjustPropagatedContribution(double[] newAverageScores, double[] oldAverageScores, double[] newSquaredScores, double[] oldSquaredScores, TreeNode from, List<TreeNode> path)
		{
			double[] ourNewAverageScores = new double[numRoles];
			double[] ourNewAverageSquaredScores = (newSquaredScores == null ? null : new double[numRoles]);
			boolean anythingToPropagate = false;
			
			for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
			{
				double delta = newAverageScores[roleIndex] - oldAverageScores[roleIndex];
				double squaredDelta = (newSquaredScores == null ? 0 : newSquaredScores[roleIndex] - oldSquaredScores[roleIndex]);

				if ( Math.abs(delta) > epsilon && (path == null || !path.contains(this)) )
				{
					double newAverageScore = averageScores[roleIndex];
					double newAverageSquaredScore = averageSquaredScores[roleIndex];
	
					if ( from != this )
					{
						int childVisits = 0;
						
						for(int index = 0; index < children.length; index++)
						{
							TreeNodeRef cr = children[index].child;
							if ( cr.node == from && cr.seq == from.seq )
							{
								childVisits = children[index].numChildVisits;
								break;
							}
						}
						
						if ( childVisits == 0 )
						{
							return;
						}
						
						if ( numVisits == 0 )
						{
							System.out.println("Adjusting incomplete node with no visits!");
						}
						newAverageScore = (averageScores[roleIndex]*numVisits + (averageScores[roleIndex]+delta)*childVisits)/(numVisits + childVisits);
						newAverageSquaredScore = (averageSquaredScores[roleIndex]*numVisits + (averageScores[roleIndex]+squaredDelta)*childVisits)/(numVisits + childVisits);
					}
					
		    		//	Keep rounding errors from sending us out of range
		    		if ( newAverageScore > 100 )
		    		{
		    			newAverageScore = 100;
		    		}
		    		else if ( newAverageScore < 0 )
		    		{
		    			newAverageScore = 0;
		    		}
		    		ourNewAverageScores[roleIndex] = newAverageScore;
		    		if ( ourNewAverageSquaredScores != null )
		    		{
		    			ourNewAverageSquaredScores[roleIndex] = newAverageSquaredScore;
		    		}
					anythingToPropagate = true;
				}
			}
			
			if ( anythingToPropagate )
			{
				for(TreeNode parent : parents)
				{
					parent.adjustPropagatedContribution(ourNewAverageScores, averageScores, ourNewAverageSquaredScores, averageSquaredScores, this, path);
				}
			}
		}

	    public void updateVisitCounts(int sampleSize, List<TreeNode> path)
	    {
	    	Iterator<TreeNode> itr = path.iterator();
	    	boolean onPath = false;
	    	TreeNode child = null;
	    	
	    	while(itr.hasNext())
	    	{
	    		if (itr.next() == this)
	    		{
	    			onPath = true;
	    			if ( itr.hasNext())
	    			{
	    				child = itr.next();
	    			}
	    			break;
	    		}
	    	}
	    	
	    	if ( children == null || onPath )
	    	{
		    	numVisits++;// += sampleSize;
		    	
		    	for(TreeNode parent : parents)
		    	{
		    		if ( !parent.complete || isSimultaneousMove || isMultiPlayer )
		    		{
		    			parent.updateVisitCounts(sampleSize, path);
		    		}
		    	}
	    	}
	    	
	    	if ( onPath && child != null )
	    	{
	    		for(int index = 0; index < children.length; index++)
	    		{
	    			if ( children[index].child.node == child )
	    			{
	    				children[index].numChildVisits++;
	    				break;
	    			}
	    		}
	    	}
	    }
	    
	    public void updateStats(double[] values, double[] squaredValues, int sampleSize, List<TreeNode> path, boolean updateVisitCounts)
	    {
	    	Iterator<TreeNode> itr = path.iterator();
	    	boolean onPath = false;
	    	TreeNode child = null;
	    	
	    	while(itr.hasNext())
	    	{
	    		if (itr.next() == this)
	    		{
	    			onPath = true;
	    			if ( itr.hasNext())
	    			{
	    				child = itr.next();
	    			}
	    			break;
	    		}
	    	}
	    	
	    	if ( children == null || onPath )
	    	{
	    		double[]	oldAverageScores = new double[numRoles];
	    		double[]	oldAverageSquaredScores = new double[numRoles];
	    		
	    		for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
	    		{
			    	oldAverageScores[roleIndex] = averageScores[roleIndex];
			    	oldAverageSquaredScores[roleIndex] = averageSquaredScores[roleIndex];
			    	
			    	if ( updateVisitCounts)
			    	{
			    		averageScores[roleIndex] = (averageScores[roleIndex]*numVisits + values[roleIndex])/(numVisits+1);
			    		averageSquaredScores[roleIndex] = (averageSquaredScores[roleIndex]*numVisits + squaredValues[roleIndex])/(numVisits+1);
				    	
			    		numVisits++;
			    		//numVisits += sampleSize;
			    		
				    	if ( onPath && child != null && (!complete || isSimultaneousMove || isMultiPlayer))
				    	{
				    		for(int index = 0; index < children.length; index++)
				    		{
				    			if ( children[index].child.node == child )
				    			{
				    				children[index].numChildVisits++;
				    				break;
				    			}
				    		}
				    	}
			    	}
			    	else
			    	{
			    		if ( numVisits == 0 )
			    		{
			    			System.out.println("Updating stats for unvisited node");
			    		}

			    		averageScores[roleIndex] = (averageScores[roleIndex]*(numVisits-1) + values[roleIndex])/(numVisits);
			    		averageSquaredScores[roleIndex] = (averageSquaredScores[roleIndex]*(numVisits-1) + squaredValues[roleIndex])/numVisits;
			    	}
			    	
			    	//if ( averageScore < 10 && numVisits > 10000 )
			    	//{
			    	//	System.out.println("!");
			    	//}
			    	if ( complete && !isSimultaneousMove && !isMultiPlayer && averageScores[roleIndex] != oldAverageScores[roleIndex] )
			    	{
			    		System.out.println("Unexpected update to complete node score");
			    	}
			    	
			    	leastLikelyWinner = -1;
			    	mostLikelyWinner = -1;
			    }
		    	
		    	for(TreeNode parent : parents)
		    	{
		    		boolean parentOnPath = path.contains(parent);
		    		
		    		if ( (!parent.complete || isSimultaneousMove || isMultiPlayer) && (parentOnPath || parent.numVisits > 0) )
		    		{
			    		if ( onPath )
			    		{
			    			parent.updateStats(values, squaredValues, sampleSize, path, updateVisitCounts);
			    		}
			    		else
			    		{
			    			parent.adjustPropagatedContribution(averageScores, oldAverageScores, averageSquaredScores, oldAverageSquaredScores, this, path);
			    		}
		    		}
		    	}
	    	}
	    }
	}
	
	private void validateAll()
	{
		if ( root != null )
			root.validate();
		
		for(Entry<ForwardDeadReckonInternalMachineState, TreeNode> e : positions.entrySet())
		{
			if ( e.getValue().decidingRoleIndex != numRoles-1 )
			{
				System.out.println("Position references bad type");
			}
			if ( !e.getValue().state.equals(e.getKey()))
			{
				System.out.println("Position state mismatch");
			}
		}
		
		int incompleteCount = 0;
		
		for(TreeNode node : transpositionTable)
		{
			if ( node != null && !node.freed )
			{
				if ( node.trimmedChildren > 0 && !node.complete )
				{
					incompleteCount++;
				}
				if ( node.decidingRoleIndex == numRoles-1 )
				{
					if ( node != positions.get(node.state) )
					{
						System.out.println("Missing reference in positions table");
						System.out.print("node state is: " + node.state + " with hash " + node.state.hashCode());
						System.out.print(positions.get(node.state));
					}
				}
			}
		}
		
		if ( incompleteCount != numIncompleteNodes )
		{
			System.out.println("Incomplete count mismatch");
		}
	}

	//	TODO - this will need to be per-role
	private Map<ForwardDeadReckonInternalMachineState, Integer> patterns = null;
	
    private double patternMatchValue(ForwardDeadReckonInternalMachineState state)
    {
    	double result = 0;
    	
    	if ( patterns != null )
    	{
    		for(Entry<ForwardDeadReckonInternalMachineState, Integer> pattern : patterns.entrySet())
    		{
    			if ( state.contains(pattern.getKey()) )
    			{
    				result += pattern.getValue();
    			}
    		}
    		
    		result /= patterns.size();
    	}
    	
    	return result;
    }

	private void emptyTree()
	{
		numUniqueTreeNodes = 0;
		numTotalTreeNodes = 0;
		numFreedTreeNodes = 0;
		numCompletedBranches = 0;
		numUsedNodes = 0;
		root = null;
		freeList.clear();
		for(int i = 0; i <= largestUsedIndex; i++)
		{
			transpositionTable[i].reset(true);
			freeList.add(transpositionTable[i]);
		}
		positions.clear();
		queuedRollouts.clear();
		completedRollouts.clear();
		numQueuedRollouts = 0;
		numCompletedRollouts = 0;
	}
	
	private TestForwardDeadReckonPropnetStateMachine underlyingStateMachine;
	private TreeNode root = null;
		
	@Override
	public String getName() {
		return "Sancho 1.22";
	}
	
	@Override
	public StateMachine getInitialStateMachine() {
		if ( transpositionTable == null )
		{
			transpositionTable = new TreeNode[transpositionTableSize];
		}
	    if ( rolloutProcessors != null )
	    {
	    	System.out.println("Stop rollout processors");
	    	for(int i = 0; i < numRolloutThreads; i++)
	    	{
	    		rolloutProcessors[i].stop();
	    	}
	    	
	    	rolloutProcessors = null;
	    }
		//GamerLogger.setFileToDisplay("StateMachine");
		//ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
		underlyingStateMachine = new TestForwardDeadReckonPropnetStateMachine(1+numRolloutThreads);
		
		emptyTree();
		System.gc();

		return underlyingStateMachine;
	}
	
	private boolean isPuzzle = false;
	private boolean isMultiPlayer = false;
	private boolean isSimultaneousMove = false;
	private int numRoles = 0;
	private int MinRawNetScore = 0;
	private int MaxRawNetScore = 100;
	private int multiRoleAverageScoreDiff = 0;
    private boolean underExpectedRangeScoreReported = false;
    private boolean overExpectedRangeScoreReported = false;
    private MachineState targetState = null;
	
	private int unNormalizedStateDistance(MachineState queriedState, MachineState targetState)
	{
		int matchCount = 0;
		
		for(GdlSentence s : targetState.getContents())
		{
			if ( queriedState.getContents().contains(s))
			{
				matchCount++;
			}
		}
		
		return targetState.getContents().size() - matchCount;
	}

	private void disableGreedyRollouts()
	{
		System.out.println("Disabling greedy rollouts");
		underlyingStateMachine.disableGreedyRollouts();
	    if ( rolloutProcessors != null )
	    {
	    	for(int i = 0; i < numRolloutThreads; i++)
	    	{
	    		rolloutProcessors[i].disableGreedyRollouts();
	    	}
	    }
	}
	
	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		ourRole = getRole();
		
		numRoles = underlyingStateMachine.getRoles().size();
		initializeRoleOrdering();
		
		isPuzzle = (numRoles == 1);
		isMultiPlayer = (numRoles > 2);
		
		MinRawNetScore = 0;
		MaxRawNetScore = 100;
	    underExpectedRangeScoreReported = false;
	    overExpectedRangeScoreReported = false;
	    completeSelectionFromIncompleteParentWarned = false;
	    highestRolloutScoreSeen = -100;
		
	    if ( rolloutProcessors == null && numRolloutThreads > 0 )
	    {
	    	rolloutProcessors = new RolloutProcessor[numRolloutThreads];
	    	
	    	for(int i = 0; i < numRolloutThreads; i++)
	    	{
	    		rolloutProcessors[i] = new RolloutProcessor(underlyingStateMachine.createInstance());
	    		rolloutProcessors[i].start();
	    	}
	    }	    
		
		int observedMinNetScore = Integer.MAX_VALUE;
		int observedMaxNetScore = Integer.MIN_VALUE;
		int simulationsPerformed = 0;
		int multiRoleSamples = 0;
		boolean hasPseudoSimultaneous = false;	
		boolean greedyRolloutsDisabled = false;

		targetState = null;

		multiRoleAverageScoreDiff = 0;
		
		ForwardDeadReckonInternalMachineState initialState = underlyingStateMachine.createInternalState(getCurrentState());
		ForwardDeadReckonInternalMachineState sampleState = initialState;
		
		//	Sample to see if multiple roles have multiple moves available
		//	implying this must be a simultaneous move game
		//	HACK - actually only count games where both players can play the
		//	SAME move - this gets blocker but doesn't include fully factored
		//	games like C4-simultaneous or Chinook (but it's a hack!)
		isSimultaneousMove = false;
		
		while(!isSimultaneousMove && !underlyingStateMachine.isTerminal(sampleState))
		{
			boolean	roleWithChoiceSeen = false;
			List<Move> jointMove = new LinkedList<Move>();
			Set<Move> allMovesInState = new HashSet<Move>();
			
			for(Role role : underlyingStateMachine.getRoles())
			{
				List<Move> legalMoves = underlyingStateMachine.getLegalMoves(sampleState, role);
				
				if ( legalMoves.size() > 1 )
				{
					for(Move move : legalMoves)
					{
						if ( allMovesInState.contains(move) )
						{
							isSimultaneousMove = true;
							break;
						}
						else
						{
							allMovesInState.add(move);
						}
					}
					
					if ( roleWithChoiceSeen )
					{
						hasPseudoSimultaneous = true;
					}
					
					roleWithChoiceSeen = true;
				}
				jointMove.add(legalMoves.get(r.nextInt(legalMoves.size())));
			}
			
			if ( !isSimultaneousMove )
			{
				sampleState = underlyingStateMachine.getNextState(sampleState, jointMove);
			}
		}

		if ( isSimultaneousMove )
		{
			System.out.println("Game is a simultaneous turn game");
		}
		else if ( hasPseudoSimultaneous )
		{
			System.out.println("Game is pseudo-simultaneous (factorizable?)");
		}
		else
		{
			System.out.println("Game is not a simultaneous turn game");
		}
		
		if ( isSimultaneousMove || hasPseudoSimultaneous )
		{
			if ( !greedyRolloutsDisabled )
			{
				greedyRolloutsDisabled = true;
				disableGreedyRollouts();
			}
		}
		
		//	Simulate and derive a few basic stats:
		//	1) Is the game a puzzle?
		//	2) For each role what is the largest and the smallest score that seem reachable and what are the corresponding net scores
		long simulationStopTime = timeout - 5000;
		long simulationStartTime = System.currentTimeMillis();
		
		int[] rolloutStats = new int[2];
		int maxNumTurns = 0;
		int minNumTurns = Integer.MAX_VALUE;
		double averageBranchingFactor = 0;
		double averageNumTurns = 0;
		double averageSquaredNumTurns = 0;
	    
		while(System.currentTimeMillis() < simulationStopTime)
		{
			simulationsPerformed++;
			
			sampleState = new ForwardDeadReckonInternalMachineState(initialState);

			underlyingStateMachine.getDepthChargeResult(initialState, getRole(), rolloutStats);
			
	    	int netScore = netScore(underlyingStateMachine, null);
	    	
	    	averageNumTurns = (averageNumTurns*(simulationsPerformed-1) + rolloutStats[0])/simulationsPerformed;
	    	averageSquaredNumTurns = (averageSquaredNumTurns*(simulationsPerformed-1) + rolloutStats[0]*rolloutStats[0])/simulationsPerformed;
	    	if ( rolloutStats[0] < minNumTurns)
	    	{
	    		minNumTurns = rolloutStats[0];
	    	}
	    	if ( rolloutStats[0] > maxNumTurns)
	    	{
	    		maxNumTurns = rolloutStats[0];
	    	}
	    	averageBranchingFactor = (averageBranchingFactor*(simulationsPerformed-1) + rolloutStats[1])/simulationsPerformed;
	    	
	    	//System.out.println("Saw score of " + netScore);
	    	if ( netScore < observedMinNetScore )
	    	{
	    		observedMinNetScore = netScore;
	    	}

	    	if ( netScore > observedMaxNetScore )
	    	{
	    		observedMaxNetScore = netScore;
	    	}
	    	
	    	for(Role role : underlyingStateMachine.getRoles())
    	    {
    	    	int roleScore = underlyingStateMachine.getGoal(sampleState, role);
    	    	
    	    	if ( !role.equals(ourRole) && isMultiPlayer )
    	    	{
    	    		//	If there are several enemy players involved extract a measure
    	    		//	of their goal correlation
    	    		for(Role role2 : underlyingStateMachine.getRoles())
    	    		{
    	    			if ( !role2.equals(ourRole) && !role2.equals(role) )
    	    			{
    	    				int role2Score = underlyingStateMachine.getGoal(sampleState, role2);
    	    				
    	    				multiRoleSamples++;
    	    				multiRoleAverageScoreDiff += Math.abs(role2Score - roleScore);
    	    			}
    	    		}
    	    	}
    	    }
		}
		
	    double stdDevNumTurns = Math.sqrt(averageSquaredNumTurns - averageNumTurns*averageNumTurns);
	    
		System.out.println("Range of lengths of sample games seen: [" + minNumTurns + "," + maxNumTurns + "], branching factor: " + averageBranchingFactor);
		System.out.println("Average num turns: " + averageNumTurns);
		System.out.println("Std deviation num turns: " + stdDevNumTurns);
		
		explorationBias = 15/(averageNumTurns - stdDevNumTurns) + 0.5;
		if ( explorationBias < 0.5 )
		{
			explorationBias = 0.5;
		}
		else if ( explorationBias > 1.2 )
		{
			explorationBias = 1.2;
		}
		System.out.println("Set explorationBias to " + explorationBias);
		
		if( underlyingStateMachine.numRolloutDecisionNodeExpansions > 0)
		{
			System.out.println("Greedy rollout terminal discovery effectiveness: " + (underlyingStateMachine.greedyRolloutEffectiveness*100)/underlyingStateMachine.numRolloutDecisionNodeExpansions);
		}
		
		if ( simulationsPerformed > 100 )
		{
			if ( multiRoleSamples > 0 )
			{
				multiRoleAverageScoreDiff /= multiRoleSamples;
			}
		}
		else
		{
			observedMinNetScore = 0;
			observedMaxNetScore = 100;
			multiRoleAverageScoreDiff = 0;
		}
		
		if ( minNumTurns == maxNumTurns ||
			 ((averageBranchingFactor > 40 || stdDevNumTurns < 0.15*averageNumTurns || underlyingStateMachine.greedyRolloutEffectiveness < underlyingStateMachine.numRolloutDecisionNodeExpansions/3) &&
			  !isPuzzle) )
		{
			if ( !greedyRolloutsDisabled )
			{
				greedyRolloutsDisabled = true;
				disableGreedyRollouts();
			    
			    //	Scale up the estimate of simulation rate since we'll be running without the overhead
			    //	of greedy rollouts (which is proportional to the branching factor)
			    simulationsPerformed *= averageBranchingFactor/2;
			}
		}
		
		//	Special case handling for puzzles with hard-to-find wins
		if ( isPuzzle && observedMinNetScore == observedMaxNetScore && observedMaxNetScore < 100 )
		{
			//	8-puzzle type stuff
			System.out.println("Puzzle with no observed solution");
		
			MachineState terminalState;
			Set<MachineState> goalStates = underlyingStateMachine.findGoalStates(getRole(), 90, 100, 20);
			//Set<MachineState> goalStates = underlyingStateMachine.findTerminalStates(100,20);
			Set<MachineState> cleanedStates = new HashSet<MachineState>();
			
			for(MachineState state : goalStates)
			{
				Set<GdlSentence> eliminatedSentences = new HashSet<GdlSentence>();
				
				for(GdlSentence s : state.getContents())
				{
					int count = 0;
					
					for(MachineState secondState : goalStates)
					{
						if ( state != secondState &&
							 unNormalizedStateDistance(state, secondState ) == 1 &&
							 !secondState.getContents().contains(s))
						{
							count++;
						}
					}
					
					if ( count > 1 )
					{
						eliminatedSentences.add(s);
					}
				}
				
				MachineState cleaned = new MachineState(new HashSet<GdlSentence>(state.getContents()));
				cleaned.getContents().removeAll(eliminatedSentences);
				
				cleanedStates.add(cleaned);
			}
			
			if ( cleanedStates.isEmpty() )
			{
				targetState = null;
			}
			else
			{
				terminalState = cleanedStates.iterator().next();
				targetState = terminalState;
				goalState = null;
				nextGoalState = null;
				bestScoreGoaled = -1;
				bestSeenHeuristicValue = 0;
				
				System.out.println("Found target state: " + terminalState);
			}
		}
		
		if ( isPuzzle )
		{
			System.out.println("Game is a 1-player puzzle");
		}
		else if ( isMultiPlayer )
		{
			System.out.println("Game is a 3+-player game");
		}
		else
		{
			System.out.println("Is 2 player game");
		}
		
		System.out.println("Min raw score = " + observedMinNetScore + ", max = " + observedMaxNetScore);
		System.out.println("multiRoleAverageScoreDiff = " + multiRoleAverageScoreDiff);

		if ( observedMinNetScore == observedMaxNetScore )
		{
			observedMinNetScore = 0;
			observedMaxNetScore = 100;
			
			System.out.println("No score discrimination seen during simulation - resetting to [0,100]");
		}
		
		if ( isPuzzle )
		{
			observedMinNetScore = 0;
			observedMaxNetScore = 100;
			
			System.out.println("Game is a puzzle so not normalizing scores");
		}
		
		//	Normalize score ranges
		MinRawNetScore = observedMinNetScore;
		MaxRawNetScore = observedMaxNetScore;
		multiRoleAverageScoreDiff = (multiRoleAverageScoreDiff*100)/(MaxRawNetScore - MinRawNetScore);
		
		if (numRolloutThreads == 0)
		{
			rolloutSampleSize = 1;
		}
		else
		{
			rolloutSampleSize = (int) (simulationsPerformed/(5*(simulationStopTime - simulationStartTime)));
			if ( rolloutSampleSize < 1 )
			{
				rolloutSampleSize = 1;
			}
			else if ( rolloutSampleSize > 100)
			{
				rolloutSampleSize = 100;
			}
		}
		
		System.out.println(simulationsPerformed*1000/(simulationStopTime - simulationStartTime) + " simulations/second performed - setting rollout sample size to " + rolloutSampleSize);
		
		//	TOTAL HACK FOR MAX KNIGHTS
		if ( false )
		{
			patterns = new HashMap<ForwardDeadReckonInternalMachineState, Integer>();
			GdlSentence[][] boardKnightProps = new GdlSentence[9][9];
			
			Pattern propParser = Pattern.compile("cell ([a-i]) ([1-9]) wn");
			for(GdlSentence baseProp : underlyingStateMachine.getBasePropositions())
			{
				String propName = baseProp.toString();
				Matcher matcher = propParser.matcher(propName);
				
				if ( matcher.find() )
				{
					int i = (int)matcher.group(1).charAt(0) - (int)'a';
					int j = Integer.parseInt(matcher.group(2)) - 1;
					
					boardKnightProps[i][j] = baseProp;
				}
			}
			
			Set<GdlSentence> patternSet = new HashSet<GdlSentence>();
			MachineState patternState = new MachineState(patternSet);
			ForwardDeadReckonInternalMachineState internalPatternState;
			
			for(int i = 0; i < 7; i++)
			{
				for(int j = 0; j < 7; j++)
				{
					patternSet.add(boardKnightProps[i][j]);
					patternSet.add(boardKnightProps[i+1][j+1]);
					
					internalPatternState = underlyingStateMachine.createInternalState(patternState);
					
					patterns.put(internalPatternState, new Integer(1));
					
					patternSet.clear();
					patternSet.add(boardKnightProps[i][j]);
					patternSet.add(boardKnightProps[i+1][j]);
					
					internalPatternState = underlyingStateMachine.createInternalState(patternState);
					
					patterns.put(internalPatternState, new Integer(-1));
					
					patternSet.clear();
					patternSet.add(boardKnightProps[i][j]);
					patternSet.add(boardKnightProps[i][j+1]);
					
					internalPatternState = underlyingStateMachine.createInternalState(patternState);
					
					patterns.put(internalPatternState, new Integer(-1));
				}
			}
			
			patternSet.clear();
			patternSet.add(boardKnightProps[0][0]);
			patternSet.add(boardKnightProps[7][0]);
			
			internalPatternState = underlyingStateMachine.createInternalState(patternState);
			
			patterns.put(internalPatternState, new Integer(-50));
			
			patternSet.clear();
			patternSet.add(boardKnightProps[0][0]);
			patternSet.add(boardKnightProps[0][7]);
			
			internalPatternState = underlyingStateMachine.createInternalState(patternState);
			
			patterns.put(internalPatternState, new Integer(-50));
			
			patternSet.clear();
			patternSet.add(boardKnightProps[7][7]);
			patternSet.add(boardKnightProps[7][0]);
			
			internalPatternState = underlyingStateMachine.createInternalState(patternState);
			
			patterns.put(internalPatternState, new Integer(-50));
			
			patternSet.clear();
			patternSet.add(boardKnightProps[7][7]);
			patternSet.add(boardKnightProps[0][7]);
			
			internalPatternState = underlyingStateMachine.createInternalState(patternState);
			
			patterns.put(internalPatternState, new Integer(-50));
		}
		
		if ( ProfilerContext.getContext() != null )
		{
			GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
		}
	}
	
	private MachineState goalState = null;
	private int goalDepth;
	private MachineState nextGoalState = null;
	private int bestScoreGoaled = -1;
	private Map<GdlSentence,Integer> terminalSentenceVisitedCounts = null;
	private HashMap<MachineState,Integer> considered = new HashMap<MachineState,Integer>();
	private enum HeuristicType { HEURISTIC_TYPE_EXPLORE_AWAY, HEURISTIC_TYPE_EXPLORE_NEW, HEURISTIC_TYPE_GOAL_PROXIMITY, HEURISTIC_TYPE_GOAL_VALUE, HEURISTIC_TYPE_INFERRED_PROPOSITION_VALUE };
	private HeuristicType nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
	private Set<GdlSentence> targetPropositions = null;
	private int bestWeightedExplorationResult = -1;
	private Set<MachineState> visitedStates = new HashSet<MachineState>();
	
	private int stateDistance(MachineState queriedState, MachineState targetState)
	{
		return 98*unNormalizedStateDistance(queriedState, targetState)/targetState.getContents().size();
	}
	
	private int bestSeenHeuristicValue = 0;
	private int heuristicValue(MachineState state, HeuristicType type)
	{
		int result = 0;
		
		switch(type)
		{
		case HEURISTIC_TYPE_GOAL_PROXIMITY:
			result = (100 - stateDistance(state, targetState));
			if ( result > bestSeenHeuristicValue )
			{
				System.out.println("Found heuristic value of " + result + " (goaled value is " + bestScoreGoaled + ") in state " + state);
				bestSeenHeuristicValue = result;
				if ( result > bestScoreGoaled )
				{
					System.out.println("Setting as next goal state");
					nextGoalState = state;
					goalState = null;
					bestScoreGoaled = result;
					nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
				}
				else if ( result == bestScoreGoaled && goalState == null)
				{
					int explorationResult = 100;
					
					for(MachineState oldState : visitedStates)
					{
						int distance = unNormalizedStateDistance(state, oldState);

						if ( distance < explorationResult )
						{
							explorationResult = distance;
						}
					}
					
					if ( explorationResult > 1 )
					{
						System.out.println("Setting as next goal state at equal value to a previous one");
						nextGoalState = state;
						goalState = null;
						bestScoreGoaled = result;							
						nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_AWAY;
					}
					else if ( goalState != null && !state.equals(goalState) )
					{
						result = 0;
					}
				}
				else if ( goalState != null && !state.equals(goalState) )
				{
					result = 0;
				}
			}
			else if ( goalState != null && !state.equals(goalState) )
			{
				result = 0;
			}
			break;
		case HEURISTIC_TYPE_EXPLORE_AWAY:
			int matchCount = 0;
			
			for(GdlSentence s : targetPropositions)
			{
				if ( state.getContents().contains(s))
				{
					matchCount++;
				}
			}
			
			result = (4*(100*matchCount)/targetPropositions.size() + (100 - stateDistance(state, targetState)))/5;
			
			if ( result > bestWeightedExplorationResult )
			{
				//System.out.println("Setting goal state for new region to: " + state);
				bestWeightedExplorationResult = result;
				nextGoalState = state;
				nextExploreType = HeuristicType.HEURISTIC_TYPE_EXPLORE_NEW;
			}
			break;
		case HEURISTIC_TYPE_EXPLORE_NEW:
			int weightedExplorationResult = 0;
			
			for(Entry<GdlSentence, Integer> e : terminalSentenceVisitedCounts.entrySet())
			{
				if (state.getContents().contains(e.getKey()))
				{
					int matchValue = (100*(visitedStates.size() - e.getValue()))/visitedStates.size();
					
					weightedExplorationResult += matchValue;
				}
			}
			
			if ( weightedExplorationResult > bestWeightedExplorationResult )
			{
				//System.out.println("Setting goal state for new region to: " + state);
				bestWeightedExplorationResult = weightedExplorationResult;
				nextGoalState = state;
			}
			result = (4*weightedExplorationResult + (100 - stateDistance(state, targetState)))/5;
			break;
		}
		
		return result;
	}
	
	private int searchTree(MachineState state, Move move, int depth, HeuristicType searchType) throws TransitionDefinitionException, GoalDefinitionException, MoveDefinitionException
	{
		List<Move> pseudoJointMove = new LinkedList<Move>();
		pseudoJointMove.add(move);
		int result;
		
		MachineState nextState = underlyingStateMachine.getNextState(state, pseudoJointMove);
		if ( considered.containsKey(nextState) )
		{
			return considered.get(nextState);
		}

		if ( underlyingStateMachine.isTerminal(nextState))
		{
			result = underlyingStateMachine.getGoal(nextState, getRole());

			if ( result > bestScoreGoaled )
			{
				goalState = null;
				nextGoalState = nextState;
				bestScoreGoaled = result;
			}
			else if ( goalState != null && nextState.equals(goalState) )
			{
				result = bestScoreGoaled;
			}
			else
			{
				result = 0;
			}
		}
		else if ( goalState != null && nextState.equals(goalState) )
		{
			System.out.println("Encountered goaled node, returning score of " + bestScoreGoaled);
			result = bestScoreGoaled;
		}
		else if ( depth == 0 )
		{
			result = heuristicValue(nextState, searchType);
		}
		else
		{
			List<Move> moves = underlyingStateMachine.getLegalMoves(nextState, getRole());
			
			int bestScore = -1;
			
			for(Move nextMove : moves)
			{
				int score = searchTree(nextState, nextMove, depth-1, searchType);
				
				if ( score > bestScore )
				{
					bestScore = score;
				}
			}
			
			result = bestScore;
		}
		
		considered.put(nextState, result);
		
		return result;
	}
	
	private Move selectPuzzleMove(List<Move> moves, long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		long totalTime = timeout - System.currentTimeMillis();
		int bestScore = -1;
		Move bestMove = moves.get(0);
		visitedStates.add(getCurrentState());
		
		if ( getCurrentState().equals(goalState) )
		{
			System.out.println("Reached goal state: " + goalState);
			goalState = null;
			nextGoalState = null;
		}
		else
		{
			System.out.println("Current goal state is: " + goalState);
		}
		
		terminalSentenceVisitedCounts = new HashMap<GdlSentence,Integer>();
		
		for(GdlSentence s : targetState.getContents())
		{
			int count = 0;
			
			for(MachineState state : visitedStates)
			{
				if ( state.getContents().contains(s))
				{
					count++;
				}
			}
			
			terminalSentenceVisitedCounts.put(s,  count);
		}
		
		int depth = (goalState != null ? --goalDepth : 0);
		HeuristicType searchType = HeuristicType.HEURISTIC_TYPE_GOAL_PROXIMITY;

		if ( depth < 0 )
		{
			depth = 0;
			goalState = null;
			
			System.out.println("Unexpectedly reached goal depth without encountering goal state - current state is: " + getCurrentState());
		}
		
		bestScore = -1;
		bestMove = null;
		
		while(System.currentTimeMillis() < timeout - totalTime*3/4  && bestScore < 90)
		{
			depth++;
			
			for(Move move : moves)
			{
				considered.clear();
				
				int score = searchTree(getCurrentState(), move, depth, searchType);
				if ( score > bestScore )
				{
					bestScore = score;
					bestMove = move;
				}
			}
			
			//System.out.println("Best score at depth " + depth + ": " + bestScore);
		}
		
		System.out.println("Achieved search depth of " + depth);
		System.out.println("Best move: " + bestMove + ": " + bestScore);
		
		if ( goalState == null && nextGoalState != null)
		{
			goalDepth = depth;
			goalState = nextGoalState;
			System.out.println("Set goal state of: " + goalState);
		}
		
		if ( goalState == null && bestScore <= bestScoreGoaled )
		{		
			targetPropositions = new HashSet<GdlSentence>();
			for(GdlSentence s : targetState.getContents())
			{
				if ( !getCurrentState().getContents().contains(s))
				{
					targetPropositions.add(s);
				}
			}
			System.out.println("Searching for a new state region with explore type " + nextExploreType);

			depth = 1;
			
			while(System.currentTimeMillis() < timeout && depth <= 6)
			{
				bestScore = -1;
				bestMove = null;
				bestWeightedExplorationResult = -1;
				
				for(Move move : moves)
				{
					considered.clear();
					int score = searchTree(getCurrentState(), move, depth, nextExploreType);
					//int heuristicScore = minEval(getCurrentState(), move, -1, 101, 1, HeuristicType.HEURISTIC_TYPE_GOAL_PROXIMITY);
					
					//System.out.println("Move " + move + " has exploration score: " + score + " with heuristic score " + heuristicScore);
					if ( score > bestScore )//&& heuristicScore > 10)
					{
						bestScore = score;
						bestMove = move;
						//bestHeuristic = heuristicScore;
					}
					//else if ( score == bestScore )
					//{
					//	if ( heuristicScore > bestHeuristic )
					//	{
					//		bestHeuristic = heuristicScore;
					//		bestMove = move;
					//	}
					//}
					depth++;
				}
			}
			
			if ( bestMove == null )
			{
				bestMove = moves.get(0);
			}
			
			goalState = nextGoalState;
			goalDepth = depth;
			System.out.println("New goal state at depth " + goalDepth + ": " + goalState);
		}
		
		return bestMove;
	}

	@Override
	public Move stateMachineSelectMove(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException,
			GoalDefinitionException {
		// We get the current start time
		long start = System.currentTimeMillis();
		long finishBy = timeout - 2500;
	    numNonTerminalRollouts = 0;
	    numTerminalRollouts = 0;
	    Move bestMove;
		ForwardDeadReckonInternalMachineState currentState = underlyingStateMachine.createInternalState(getCurrentState());
		List<Move> moves = underlyingStateMachine.getLegalMoves(currentState, ourRole);
		
	    if ( targetState != null )
	    {
	    	bestMove = selectPuzzleMove(moves, timeout);
			System.out.println("Playing best puzzle move: " + bestMove);
	    }
	    else
	    {
			//	Process anything left over from last turn's timeout
			processCompletedRollouts(finishBy);
			
			//emptyTree();
			//root = null;
			//validateAll();
			if ( root == null )
			{
				root = allocateNode(underlyingStateMachine, currentState, null);
				root.decidingRoleIndex = numRoles-1;
			}
			else
			{
				System.out.println("Searching for new root in state: " + currentState);
				TreeNode newRoot = root.findNode(currentState, underlyingStateMachine.getRoles().size()+1);
				if ( newRoot == null )
				{
					System.out.println("Unexpectedly unable to find root node in existing tree");
					emptyTree();
					root = allocateNode(underlyingStateMachine, currentState, null);
					root.decidingRoleIndex = numRoles-1;
				}
				else
				{
					System.out.println("Freeing unreachable nodes for new state: " + currentState);
					root.freeAllBut(newRoot);
					//sweepInstance++;
					
					root = newRoot;
				}
			}
			//validateAll();
	
			if ( underlyingStateMachine.isTerminal(currentState))
			{
				System.out.println("Asked to select in terminal state!");
			}
			
			if ( root.complete && root.children == null )
			{
				System.out.println("Encountered complete root with trimmed children - must re-expand");
				root.complete = false;
				numCompletedBranches--;
			}
			//int validationCount = 0;
			
			Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
			
			while(System.currentTimeMillis() < finishBy && !root.complete)
			{
				while ( numUsedNodes > transpositionTableSize - 200 )
				{
					root.disposeLeastLikelyNode();
				}
				//validateAll();
				//validationCount++;
				int numOutstandingRollouts = numQueuedRollouts - numCompletedRollouts;
				
				if ( numOutstandingRollouts < maxOutstandingRolloutRequests )
				{
					root.selectAction(finishBy);
				}
	
				if (numRolloutThreads == 0)
				{
					while( !queuedRollouts.isEmpty() )
					{
						RolloutRequest request = queuedRollouts.remove();
						
						request.process(underlyingStateMachine);
					}
				}
				//validateAll();
				processCompletedRollouts(finishBy);			
			}
			
			//validateAll();
			bestMove = root.getBestMove(true);
			
			//validateAll();
			
			System.out.println("Playing move: " + bestMove);
			System.out.println("Num total tree node allocations: " + numTotalTreeNodes);
			System.out.println("Num unique tree node allocations: " + numUniqueTreeNodes);
			System.out.println("Num tree node frees: " + numFreedTreeNodes);
			System.out.println("Num tree nodes currently in use: " + numUsedNodes);
			System.out.println("Num true rollouts added: " + numNonTerminalRollouts);
			System.out.println("Num terminal nodes revisited: " + numTerminalRollouts);
			System.out.println("Num incomplete nodes: " + numIncompleteNodes);
			System.out.println("Num completely explored branches: " + numCompletedBranches);
			System.out.println("Num winning lines seen: " + numWinningLinesSeen);
			System.out.println("Num losing lines seen: " + numLosingLinesSeen);
			System.out.println("Current rollout sample size: " + rolloutSampleSize );
			System.out.println("Highest observed rollout score: " + highestRolloutScoreSeen);
	    }
		
		if ( ProfilerContext.getContext() != null )
		{
			GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
		}

		// We get the end time
		// It is mandatory that stop<timeout
		long stop = System.currentTimeMillis();
		
		Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
		
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

	public void setNumThreads(int numThreads) {
		numRolloutThreads = numThreads;
	}

	public void setTranspositionTableSize(int tableSize) {
		transpositionTableSize = tableSize;
	}

}
