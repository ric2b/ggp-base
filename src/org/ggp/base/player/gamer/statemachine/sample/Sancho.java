package org.ggp.base.player.gamer.statemachine.sample;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlFunction;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.profile.ProfilerSampleSetSimple;
import org.ggp.base.util.propnet.polymorphic.analysis.Analyser;
import org.ggp.base.util.propnet.polymorphic.analysis.PieceHeuristicAnalyser;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveSet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionCrossReferenceInfo;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine.MoveWeights;

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
    private boolean completeSelectionFromIncompleteParentWarned = false;
	private int numSelectionsThroughIncompleteNodes = 0;
	private int numReExpansions = 0;
    
    private Map<ForwardDeadReckonInternalMachineState, TreeNode> positions = new HashMap<ForwardDeadReckonInternalMachineState,TreeNode>();

    private final boolean freeCompletedNodeChildren = true;
    private boolean useGoalHeuristic = false;
    private int rolloutSampleSize = 4;
    private int transpositionTableSize = 2000000;
    private final int maxOutstandingRolloutRequests = 4;
    private int numRolloutThreads = 4;
    private double explorationBias = 1.0;
    private double minExplorationBias = 0.5;
    private double maxExplorationBias = 1.2;
    private final double competitivenessBonus = 2;
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
    private double[] bonusBuffer = null;
    private double[] roleRationality = null;
    private Object treeLock = new Object();
    
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
	private int lowestRolloutScoreSeen = 1000;
	
	private Object countLock = new Object();
	private int dequeuedRollouts = 0;
	private int enqueuedCompletedRollouts = 0;
	
    private class RolloutRequest
    {
    	public TreeNodeRef								node;
    	public ForwardDeadReckonInternalMachineState	state;
    	public double[]									averageScores;
    	public double[]									averageSquaredScores;
    	public int										sampleSize;
	    public TreePath									path;
	    public MoveWeights								moveWeights;
	    public MoveWeights								playedMoveWeights;
	    
	    public RolloutRequest()
	    {
	    	averageScores = new double[numRoles];
	    	averageSquaredScores = new double[numRoles];
	    }
	    
	    public  void process(TestForwardDeadReckonPropnetStateMachine stateMachine) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	    {
			ProfileSection methodSection = new ProfileSection("TreeNode.rollOut");
			try
			{
				synchronized(countLock)
				{
					dequeuedRollouts++;
				}
				double[] scores = new double[numRoles];
				
				//playedMoveWeights = stateMachine.createMoveWeights();
				
				for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
				{
					averageScores[roleIndex] = 0;
					averageSquaredScores[roleIndex] = 0;
				}
				
				for(int i = 0; i < sampleSize; i++)
				{
					//List<ForwardDeadReckonLegalMoveInfo> playedMoves = new LinkedList<ForwardDeadReckonLegalMoveInfo>();
					
					//System.out.println("Perform rollout from state: " + state);
		        	numNonTerminalRollouts++;
		        	stateMachine.getDepthChargeResult(state, ourRole, null, null, null);//moveWeights, playedMoves);
		        	
		        	for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
		        	{
		        		int score = stateMachine.getGoal(roleIndexToRole(roleIndex));
			        	averageScores[roleIndex] += score;
			        	averageSquaredScores[roleIndex] += score*score;
			        	scores[roleIndexToRawRoleIndex(roleIndex)] = score;
			        	
			        	if ( roleIndex == 0 )
			        	{
			        		if ( score > highestRolloutScoreSeen )
				        	{
			        			highestRolloutScoreSeen = score;
				        	}
			        		if ( score < lowestRolloutScoreSeen )
				        	{
			        			lowestRolloutScoreSeen = score;
				        	}
			        	}
		        	}
		        	//int score = netScore(stateMachine, null);
		        	
		        	//playedMoveWeights.addResult(scores, playedMoves);
				}
				
				for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
				{
					averageScores[roleIndex] /= sampleSize;
					averageSquaredScores[roleIndex] /= sampleSize;
				}
				
				completedRollouts.add(this);
				synchronized(countLock)
				{
					enqueuedCompletedRollouts++;
				}
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
    	public double	sampleWeight = 0;
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
        			int score = stateMachine.getGoal(state, role);
        			if ( score > bestEnemyScore )
        			{
        				bestEnemyScore = score;
        			}
        		}
        		else
        		{
        			result = stateMachine.getGoal(state, role);
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
	
	private int[] rootPieceCounts = null;
	private double[] heuristicStateValueBuffer = null;
	private double[] heuristicStateValue(ForwardDeadReckonInternalMachineState state, TreeNode previousNode)
	{
		double total = 0;
		double rootTotal = 0;
		
		for(int i = 0; i < numRoles; i++)
		{
			int numPieces = pieceStateMaps[i].intersectionSize(state);
			int previousNumPieces = pieceStateMaps[i].intersectionSize(previousNode.state);
			heuristicStateValueBuffer[i] = numPieces - rootPieceCounts[i];
			total += numPieces;
			rootTotal += rootPieceCounts[i];
			
			//	Counter-weight exchange sequences slightly to remove the first-capture bias
			//	at least to first order
			if ( numPieces == rootPieceCounts[i] && previousNumPieces < rootPieceCounts[i] )
			{
				heuristicStateValueBuffer[i] += 0.1;
				total += 0.1;
			}
			else if ( numPieces == rootPieceCounts[i] && previousNumPieces > rootPieceCounts[i] )
			{
				heuristicStateValueBuffer[i] -= 0.1;
				total -= 0.1;
			}
		}
		
		for(int i = 0; i < numRoles; i++)
		{
			double weight;
			
			if ( rootTotal != total )
			{
				double proportion = (heuristicStateValueBuffer[i] - (total-rootTotal)/numRoles)/(total/numRoles);
				weight = 1/(1+Math.exp(-proportion*10));
			}
			else
			{
				weight = 0.5;
			}
			heuristicStateValueBuffer[i] = 100*weight;

			//	Normalize against root score since this is relative to the root state material balance
			//	Only do this if the root has had enough visits to have a credible estimate
			if ( root.numVisits > 50 )
			{
				if ( heuristicStateValueBuffer[i] > 50 )
				{
					heuristicStateValueBuffer[i] = root.averageScores[i] + (100-root.averageScores[i])*(heuristicStateValueBuffer[i]-50)/50;
				}
				else
				{
					heuristicStateValueBuffer[i] = root.averageScores[i] - (root.averageScores[i])*(50-heuristicStateValueBuffer[i])/50;
				}
			}
		}
		
		
		return heuristicStateValueBuffer;
	}
	
	private int heuristicSampleWeight = 10;
	
    private void processCompletedRollouts()
    {
		//ProfileSection methodSection = new ProfileSection("processCompletedRollouts");
		//try
		//{
	    	//	Process nay outstanding node completions first, as their processing may
	    	//	have been interrupted due to running out of time at the end of the previous
	    	//	turn's processing
			processNodeCompletions();
			
			RolloutRequest request;
			
	    	while((request = completedRollouts.poll()) != null)
	    	{
	    		TreeNode 	   node = request.node.node;
	    		
	    		//masterMoveWeights.accumulate(request.playedMoveWeights);
	    		
	    		if ( request.node.seq == node.seq && !node.complete )
	    		{
	    			request.path.resetCursor();
					//validateAll();
	    			synchronized(treeLock)
	    			{
	    				node.updateStats(request.averageScores, request.averageSquaredScores, request.sampleSize, request.path, false);
	    			}
					//validateAll();
		    		processNodeCompletions();
					//validateAll();
	    		}
	    		
	    		numCompletedRollouts++;
	    	}
		//}
		//finally
		//{
		//	methodSection.exitScope();
		//}
    }
	
	private void processNodeCompletions()
	{
		while(!completedNodeQueue.isEmpty())
		{
			//validateAll();
			TreeNode node = completedNodeQueue.remove(0);
			
			synchronized(treeLock)
			{
				if ( !node.freed )
				{
					node.processCompletion();
				}
			}
		}
	}
  
	//	Array of roles reordered so our role is first
	private Role[] reorderedRoles = null;
    private Move[] canonicallyOrderedMoveBuffer = null;
    private int[]  roleOrderMap = null;
    
	private void initializeRoleOrdering()
	{
		reorderedRoles = new Role[numRoles];
		roleOrderMap = new int[numRoles];
		canonicallyOrderedMoveBuffer = new Move[numRoles];
		
		reorderedRoles[0] = ourRole;
		
		int roleIndex = 1;
		int rawRoleIndex = 0;
		for(Role role : underlyingStateMachine.getRoles())
		{
			if ( role.equals(ourRole))
			{
				roleOrderMap[0] = rawRoleIndex;
			}
			else
			{	
				roleOrderMap[roleIndex] = rawRoleIndex;
				reorderedRoles[roleIndex++] = role;
			}
			rawRoleIndex++;
		}
	}
	
	private Role roleIndexToRole(int roleIndex)
	{
		return reorderedRoles[roleIndex];
	}
	
	private int roleIndexToRawRoleIndex(int roleIndex)
	{
		return roleOrderMap[roleIndex];
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
				
				//parent.adjustDescendantCounts(result.descendantCount+1);
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
			jointPartialMove = new ForwardDeadReckonLegalMoveInfo[numRoles];
		}
		
		int			numChildVisits = 0;
		TreeNodeRef	child;
		private 	ForwardDeadReckonLegalMoveInfo[] jointPartialMove;
		TreeEdge	selectAs;
		boolean		hasCachedPatternMatchValue = false;
		double		cachedPatternMatchValue;
	}
	
	private class TreePathElement
	{
		private TreeEdge 	edge;
		private double[]	scoreOverrides = null;
		
		public TreePathElement(TreeEdge edge)
		{
			this.edge = edge;
		}
		
		public void setScoreOverrides(double[] scores)
		{
			scoreOverrides = new double[numRoles];
			
			for(int i = 0; i < numRoles; i++)
			{
				scoreOverrides[i] = scores[i];
			}
		}
		
		public double[] getScoreOverrides()
		{
			return scoreOverrides;
		}
		
		public TreeNode getChildNode()
		{
			return edge.child.node;
		}
		
		public TreeEdge getEdge()
		{
			return edge;
		}
	}
	
	private class TreePath
	{
		private TreeNode root;
		private List<TreePathElement> elements = new ArrayList<TreePathElement>();
		private int index = 0;
		
		public TreePath(TreeNode root)
		{
			this.root = root;
		}
		
		public void push(TreePathElement element)
		{
			elements.add(element);
			index++;
		}
		
		public void resetCursor()
		{
			index = elements.size();
		}
		
		public boolean hasMore()
		{
			return index > 0;
		}
		
		public TreeNode	getNextNode()
		{
			index--;
			if ( hasMore() )
			{
				TreePathElement element = elements.get(index-1);
				TreeNode node = element.getChildNode();
				
				if ( node.seq == element.edge.child.seq )
				{
					return node;
				}
				else
				{
					return null;
				}
			}
			else
			{
				return root;
			}
		}
		
		public TreePathElement getCurrentElement()
		{
			if ( index == elements.size() )
			{
				return null;
			}
			else
			{
				return elements.get(index);
			}
		}
	}
	
	private class TreeNode
	{
	    static final double epsilon = 1e-6;

	    private int seq = -1;
		private int numVisits = 0;
		private int numUpdates = 0;
		private double[] averageScores;
		private double[] averageSquaredScores;
		private int[] numChoices;
		private ForwardDeadReckonInternalMachineState state;
		private int decidingRoleIndex;
		private boolean isTerminal = false;
		private TreeEdge[] children = null;
		private Set<TreeNode> parents = new HashSet<TreeNode>();
		private int trimmedChildren = 0;
		private int sweepSeq;
		//private TreeNode sweepParent = null;
		boolean freed = false;
		int trimCount = 0;
	    private int leastLikelyWinner = -1;
	    private double leastLikelyRunnerUpValue;
	    private int mostLikelyWinner = -1;
	    private double mostLikelyRunnerUpValue;
	    private boolean complete = false;
	    private boolean allChildrenComplete = false;
		
		private TreeNode() throws GoalDefinitionException
		{
			averageScores = new double[numRoles];
			averageSquaredScores = new double[numRoles];
		}
		
		private void correctParentsForCompletion(double values[])
		{
			//	Cannot do an a-priori correction of scores based on known child scores
			//	if heuristics are in use (at least not simply, so for now, just not)
			//if ( pieceStateMaps == null )
			{
				TreeNode primaryPathParent = null;
				int		 mostSelectedRouteCount = 0;
				
				for(TreeNode parent : parents)
				{
					if ( parent.numUpdates > 0 )
					{
						for(TreeEdge child : parent.children)
						{
							if ( child.child.node == this )
							{
								if ( child.numChildVisits > mostSelectedRouteCount )
								{
									mostSelectedRouteCount = child.numChildVisits;
									primaryPathParent = parent;
								}
								break;
							}
						}
					}
				}
				
				if ( primaryPathParent != null && !primaryPathParent.complete )
				{
					double[] correctedAverageScores = new double[numRoles];
					boolean propagate = true;
					boolean isDeciderCorrelated = (values[primaryPathParent.decidingRoleIndex] > averageScores[primaryPathParent.decidingRoleIndex]);
					double correctionFactor;
	
					//validateScoreVector(primaryPathParent.averageScores);
	
					double totalWeight = 0;
					
					for(int i = 0; i < numRoles; i++)
					{
						correctedAverageScores[i] = 0;
					}
					
					for(TreeEdge edge : primaryPathParent.children)
					{
						if ( edge.selectAs == edge && edge.child.seq == edge.child.node.seq )
						{
							double exploitationUct = primaryPathParent.exploitationUCT(edge, edge.child.node.decidingRoleIndex);
							
							//double weight = (exploitationUCT(edge, edge.child.node.decidingRoleIndex) + epsilon)*Math.log(edge.child.node.numVisits+1);
							//double weight = Math.exp(Math.log(primaryPathParent.numVisits)*primaryPathParent.exploitationUCT(edge, edge.child.node.decidingRoleIndex)) - 1 + epsilon;
							//double weight = exploitationUct*exploitationUct + epsilon;
							//double weight = 1/((1-exploitationUct)*(1-exploitationUct) + epsilon) - 1;
							double weight = (exploitationUct+1/Math.log(primaryPathParent.numVisits+1))*edge.child.node.numVisits + epsilon;
							
							totalWeight += weight;
							for(int i = 0; i < numRoles; i++)
							{
								correctedAverageScores[i] += weight*edge.child.node.averageScores[i];
							}
						}
					}
					
					for(int i = 0; i < numRoles; i++)
					{
						correctedAverageScores[i] /= totalWeight;
					}
	//				for(int i = 0; i < numRoles; i++)
	//				{
	//					//correctedAverageScores[i] = (primaryPathParent.averageScores[i]*(primaryPathParent.numVisits - mostSelectedRouteCount) + values[i]*correctionFactor*mostSelectedRouteCount)/(primaryPathParent.numVisits+(correctionFactor-1)*mostSelectedRouteCount);
	//					double correctedValue;
	//					
	//					if ( isDeciderCorrelated || primaryPathParent.numVisits == mostSelectedRouteCount)
	//					{
	//						//	Assume it would still have been selected and change the contribution to the correct complete one
	//						correctionFactor = 1.2;
	//					}
	//					else
	//					{
	//						//	Assume it would have been (far) less selected and just remove the contribution from this child if there are other contributors
	//						correctionFactor = 0.5;
	//					}
	//					
	//					correctedValue = (primaryPathParent.numVisits*primaryPathParent.averageScores[i] - mostSelectedRouteCount*averageScores[i] + mostSelectedRouteCount*correctionFactor*values[i])/(primaryPathParent.numVisits + (correctionFactor-1)*mostSelectedRouteCount);
	//					
	//					if ( correctedValue < 0 )
	//					{
	//						correctedValue = 0;
	//					}
	//					else if ( correctedValue > 100 )
	//					{
	//						correctedValue = 100;
	//					}
	//						
	//					correctedAverageScores[i] = correctedValue;
	//				}
					
					//validateScoreVector(correctedAverageScores);
								
					if (propagate)
					{
						//if ( correctedAverageScores[decidingRoleIndex] > average)
						primaryPathParent.correctParentsForCompletion(correctedAverageScores);
					}
					
					for(int i = 0; i < numRoles; i++)
					{
						primaryPathParent.averageScores[i] = correctedAverageScores[i];
					}
					//validateScoreVector(primaryPathParent.averageScores);
				}
			}
		}
		
		private void validateCompletionValues(double[] values)
		{
			boolean matchesAll = true;
			boolean	matchesDecider = false;
			
			if ( children != null )
			{
				for(TreeEdge edge : children)
				{
					if ( edge.selectAs == edge && edge.child.seq == edge.child.node.seq )
					{
						if ( edge.child.node.complete)
						{
							if ( edge.child.node.averageScores[decidingRoleIndex] == values[decidingRoleIndex] )
							{
								matchesDecider = true;
							}
							else
							{
								matchesAll = false;
							}
						}
						else
						{
							matchesAll = false;
						}
					}
				}
			}
			
			if ( !matchesAll && !matchesDecider )
			{
				System.out.println("Inexplicable completion!");
			}
		}
		
		private void markComplete(double[] values)
		{
			if ( !complete )
			{
				//validateCompletionValues(values);
//				if ( values[1] > 99.5 && state.toString().contains("1 1 b") && state.toString().contains("1 2 b") && state.toString().contains("1 3 b") && state.toString().contains("1 4 b"))
//				{
//					System.out.println("Impossible completion!");
//				}
				//System.out.println("Mark complete  node seq: " + seq);
				//validateAll();
				if ( numUpdates > 0 )
				{
					//validateScoreVector(averageScores);
					correctParentsForCompletion(values);
					//validateScoreVector(averageScores);
				}
				
//				if ( children != null )
//				{
//					boolean valid = false;
//					
//					for(TreeEdge edge : children)
//					{
//						if ( edge.selectAs == edge )
//						{
//							if ( edge.child.node.complete && edge.child.node.averageScores[0] == values[0] )
//							{
//								valid = true;
//								break;
//							}
//						}
//					}
//					
//					if ( !valid )
//					{
//						System.out.println("Invalid ndoe completion");
//					}
//				}
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
//					if ( parents.contains(root))
//					{
//						System.out.println("First level move completed");
//						if ( Math.abs(values[0] - 50) > 0.1 )
//						{
//						    //root.dumpTree("C:\\temp\\mctsTree.txt");
//							System.out.println("Bad completion");
//						}
//					}
//					else
//					{
//						for(TreeNode parent : parents)
//						{
//							if ( parent.parents.contains(root))
//							{
//								System.out.println("Second level move completed");
//							}
//						}
//					}
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
			//validateCompletionValues(averageScores);
			//System.out.println("Process completion of node seq: " + seq);
			//validateAll();
			//	Children can all be freed, at least from this parentage
			if ( children != null && freeCompletedNodeChildren )
			{
				for(TreeEdge edge : children)
				{
					TreeNodeRef cr = edge.child;
					if ( cr.node.seq == cr.seq )
					{
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
			}		
			
			boolean decidingRoleWin = false;
			boolean mutualWin = true;
			
			for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
			{
				if ( averageScores[roleIndex] > 99.5 )
				{
					if (roleIndex == decidingRoleIndex && (!isSimultaneousMove || roleIndex == 0 || hasSiblinglessParents() ))
					{
						decidingRoleWin = true;
					}
				}
				else
				{
					mutualWin = false;
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
					parent.checkChildCompletion(true);
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
		
		private boolean hasSiblings()
		{
			for(TreeNode parent : parents)
			{
				int numChildren = 0;
				
				if ( parent.children != null )
				{
					for(TreeEdge edge : parent.children)
					{
						if ( edge.selectAs == edge )
						{
							numChildren++;
						}
					}
				}
				
				if ( numChildren > 1 )
				{
					return true;
				}
			}
			
			return false;
		}
		
		private boolean hasSiblinglessParents()
		{
			for(TreeNode parent : parents)
			{
				if ( parent == root )
				{
					return false;
				}
				
				for(TreeNode grandParent : parent.parents)
				{
					if ( grandParent.children.length > 1 )
					{
						return false;
					}
				}
			}
			
			return true;
		}
		
		private boolean allNephewsComplete()
		{
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
     				if ( edge.selectAs == edge )
     				{
     					if ( edge.child.seq == child.seq )
	    				{
	     					if ( !child.complete )
	     					{
		    					if ( child.children != null )
			     				{
			    	    			for(TreeEdge nephewEdge : child.children)
			    	    			{
			    	    				TreeNode nephew = nephewEdge.child.node;
			    	    				
			    	    				if ( nephewEdge.child.seq != nephew.seq || !nephew.complete )
				    					{
				    						return false;
				    					}
			    	    			}
			     				}
		    					else
		    					{
		    						return false;
		    					}
	     					}
	    				}
	    				else
	    				{
	    					return false;
	    				}
     				}
    			}
    		}
    		
    		return true;
		}
		
		private void checkSiblingCompletion(double[] floorDeciderScore)
		{
			int roleIndex = (decidingRoleIndex+1)%numRoles;
			
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
    				if ( child != this && edge.child.seq == child.seq && child.children != null && !child.complete )
    				{
    					child.checkChildCompletion(false);
//    					double[] worstValues = null;
//    					double[] averageValues = new double[numRoles];
//    					boolean  allChildrenComplete = true;
//    					int		 childCount = 0;
//    					
//    	    			for(TreeEdge nephewEdge : child.children)
//    	    			{
//    	    				TreeNode nephew = nephewEdge.child.node;
//    	    				
//    	    				if ( nephewEdge.child.seq == nephew.seq && nephewEdge.selectAs == nephewEdge )
//    	    				{
//    	    					if ( !nephew.complete )
//    	    					{
//    	    						allChildrenComplete = false;
//    	    						break;
//    	    					}
//    	    					
//    	    					childCount++;
//    	    					for(int i = 0; i < numRoles; i++)
//    	    					{
//    	    						averageValues[i] += nephew.averageScores[i];
//    	    					}
//    	    					
//    	    					if ( worstValues == null || nephew.averageScores[nephew.decidingRoleIndex] < worstValues[nephew.decidingRoleIndex] )
//    	    					{
//    	    						worstValues = nephew.averageScores;
//    	    					}
//     	    				}
//    	    			}
//    	    			
//    	    			if ( allChildrenComplete )
//    	    			{
//	    					for(int i = 0; i < numRoles; i++)
//	    					{
//	    						averageValues[i] /= childCount;
//	    					}
//
//	   	    				child.markComplete((floorDeciderScore != null && floorDeciderScore[roleIndex] > worstValues[roleIndex]) ? floorDeciderScore : worstValues);
//	   	    				//child.markComplete(averageValues);
//    	    			}
    				}
    			}
    		}
 		}
		
		private boolean allCousinsExceed(TreeEdge withRespectTo, int roleIndex, double threshold)
		{
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
    				if ( child != this )
    				{
    					if ( edge.child.seq != child.seq || (child.children == null && !child.complete))
    					{
    						return false;
    					}
    					
    					if ( !child.complete )
    					{
	    	    			for(TreeEdge nephewEdge : child.children)
	    	    			{
	    	    				TreeNode nephew = nephewEdge.child.node;
	    	    				
	    	    				if ( nephewEdge.child.seq == nephew.seq &&
	    	    					 nephewEdge.jointPartialMove[nephew.decidingRoleIndex] == withRespectTo.jointPartialMove[nephew.decidingRoleIndex] &&
	    	    					 nephew.averageScores[roleIndex] < threshold)
	    	    				{
	    	    					return false;
	    	    				}
      	    				}
    	    			}
    					else if ( child.averageScores[roleIndex] < threshold )
    					{
    						return false;
    					}
     				}
    			}
    		}
    		
    		return true;
		}
		
		private boolean isBestMoveInAllUncles(Set<Move> moves, int roleIndex)
		{
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
    				if ( child != this )
    				{
    					if ( edge.child.seq != child.seq || (child.children == null && !child.complete))
    					{
    						return false;
    					}
    					
    					if ( !child.complete )
    					{
    						double bestOtherMoveScore = 0;
    						double thisMoveScore = -Double.MAX_VALUE;
    						TreeEdge bestEdge = null;
    						
	    	    			for(TreeEdge nephewEdge : child.children)
	    	    			{
	    	    				TreeNode nephew = nephewEdge.child.node;
	    	    				
	    	    				if ( nephewEdge.child.seq == nephew.seq )
	    	    				{
	    	    					if ( moves.contains(nephewEdge.jointPartialMove[nephew.decidingRoleIndex].move) )
		    	    				{
	    	    						if ( nephew.averageScores[roleIndex] > thisMoveScore )
	    	    						{
	    	    							thisMoveScore = nephew.averageScores[roleIndex];
	    	    						}
		    	    				}
	    	    					else
	    	    					{
	    	    						if ( nephew.averageScores[roleIndex] > bestOtherMoveScore )
	    	    						{
	    	    							bestOtherMoveScore = nephew.averageScores[roleIndex];
	    	    							bestEdge = nephewEdge;
	    	    						}
	    	    					}
	    	    				}
      	    				}
	    	    			
	    	    			if ( bestOtherMoveScore > thisMoveScore && thisMoveScore != -Double.MAX_VALUE )
	    	    			{
	    	    				return false;
	    	    			}
    	    			}
    					else if ( child.averageScores[roleIndex] < 99.5 )
    					{
    						return false;
    					}
     				}
    			}
    		}
    		
    		return true;
		}

		private double[] worstCompleteCousinValues(Move move, int roleIndex)
		{
			double[] result = null;
			
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
    				if ( edge.selectAs == edge )
    				{
    					if ( edge.child.seq != child.seq || (child.children == null && !child.complete))
    					{
    						return null;
    					}
    					
    					if ( !child.complete )
    					{
	    	    			for(TreeEdge nephewEdge : child.children)
	    	    			{
	    	    				TreeNode nephew = nephewEdge.child.node;
	    	    				
	    	    				if ( nephewEdge.child.seq == nephew.seq &&
	    	    					 move == nephewEdge.jointPartialMove[nephew.decidingRoleIndex].move )
	    	    				{
	    	    					if ( !nephew.complete )
	    	    					{
	    	    						return null;
	    	    					}
	    	    					if (result == null || nephew.averageScores[roleIndex] < result[roleIndex])
		    	    				{
		    	    					result = nephew.averageScores;
		    	    				}
	      	    				}
	    	    			}
    	    			}
    					else if ( result == null || child.averageScores[roleIndex] < result[roleIndex] )
    					{
    						result = child.averageScores;
    					}
     				}
    			}
    		}
    		
    		return result;
		}
		
		private void checkSiblingCompletion(TreeEdge withRespectTo)
		{
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
    				if ( child != this && edge.child.seq == child.seq && child.children != null && !child.complete )
    				{
    					child.checkChildCompletion(false);
      				}
    			}
    		}
 		}
		
		private void propagateCousinWins(TreeEdge withRespectTo)
		{
    		for(TreeNode parent : parents)
    		{
    			for(TreeEdge edge : parent.children)
    			{
    				TreeNode child = edge.child.node;
    				
    				if ( child != this && edge.child.seq == child.seq && child.children != null && !child.complete )
    				{
    	    			for(TreeEdge nephewEdge : child.children)
    	    			{
    	    				TreeNode nephew = nephewEdge.child.node;
    	    				
    	    				if ( nephewEdge.child.seq == nephew.seq &&
    	    					 nephewEdge.jointPartialMove[nephew.decidingRoleIndex] == withRespectTo.jointPartialMove[nephew.decidingRoleIndex])
    	    				{
    	    					child.markComplete(nephew.averageScores);
    	    					break;
      	    				}
    	    			}
     				}
    			}
    		}
 		}
		
		private void checkChildCompletion(boolean checkConsequentialSiblingCompletion)
		{
			boolean allImmediateChildrenComplete = true;
			double bestValue = -1000;
			double[] bestValues = null;
			double[] averageValues = new double[numRoles];
			int roleIndex = (decidingRoleIndex+1)%numRoles;
			boolean decidingRoleWin = false;
			int numPotentialDecidingRoleWins = 0;
			double[] worstDeciderScore = null;
			double[] floorDeciderScore = null;
			
			int numUniqueChildren = 0;

//			double bestIncompleteDecidingRoleVal = 0;
//			if ( isSimultaneousMove )
//			{
//				for(TreeEdge edge : children)
//				{
//					TreeNodeRef cr = edge.child;
//					if ( cr.node.seq == cr.seq && edge.selectAs == edge && !cr.node.complete )
//					{
//						if ( cr.node.averageScores[roleIndex] > bestIncompleteDecidingRoleVal )
//						{
//							bestIncompleteDecidingRoleVal = cr.node.averageScores[roleIndex];
//						}
//					}
//				}
//			}
			
			for(TreeEdge edge : children)
			{
				TreeNodeRef cr = edge.child;
				if ( cr.node.seq == cr.seq )
				{
					if ( edge.selectAs == edge )
					{
						numUniqueChildren++;

						if ( !cr.node.complete )
						{
							allImmediateChildrenComplete = false;
						}
						else
						{
							if ( worstDeciderScore == null || cr.node.averageScores[roleIndex] < worstDeciderScore[roleIndex] )
							{
								worstDeciderScore = cr.node.averageScores;
							}
							
							if ( cr.node.averageScores[roleIndex] >= bestValue )
							{
								bestValue = cr.node.averageScores[roleIndex];
								bestValues = cr.node.averageScores;
								
								if ( bestValue > 99.5 )
								{
									numPotentialDecidingRoleWins++;
									
									//	Win for deciding role which they will choose unless it is also
									//	a mutual win
									boolean mutualWin = true;
									
									for(int i = 0; i < numRoles; i++)
									{
										if ( cr.node.averageScores[i] < 99.5 )
										{
											mutualWin = false;
											break;
										}
									}
									
									if ( !decidingRoleWin )
									{
										decidingRoleWin |= !mutualWin;
										
										if ( decidingRoleWin && isSimultaneousMove )
										{
											//	Only complete on this basis if this move is our choice (complete info)
											//	or wins in ALL cousin states also
											if ( roleIndex != 0 && hasSiblings() )
											{
												//double averageCousinValue = 100*getAverageCousinMoveValue(edge, roleIndex);
		//											double cousinThreshold = worstCousinValue(edge, roleIndex);
		//											if( parents.contains(root))
		//											{
		//												System.out.println("Worst cousin val: " + cousinThreshold);
		//												System.out.println("Incomplete threshold: " + bestIncompleteDecidingRoleVal);
		//											}
		//											if ( cousinThreshold < bestIncompleteDecidingRoleVal )
		//											{
		//												decidingRoleWin = false;
		//											}
		//											else
		//											{
		//												if ( checkConsequentialSiblingCompletion )
		//												{
		//													checkSiblingCompletion(edge);
		//												}
		//												//propagateCousinWins(edge);
		//											}
												Set<Move> equivalentMoves = new HashSet<Move>();
												for(TreeEdge siblingEdge : children)
												{
													if ( siblingEdge.selectAs == edge )
													{
														equivalentMoves.add(siblingEdge.jointPartialMove[roleIndex].move);
													}
												}
												if ( !isBestMoveInAllUncles(equivalentMoves, roleIndex))
												{
													decidingRoleWin = false;
												}
												else
												{
													if ( checkConsequentialSiblingCompletion )
													{
														checkSiblingCompletion(edge);
													}
												}
											}
										}
									}
								}
							}
							
							if ( isSimultaneousMove && !decidingRoleWin && roleIndex != 0 &&
								 (floorDeciderScore == null || floorDeciderScore[roleIndex] < edge.child.node.averageScores[roleIndex]))
							{
								//	Find the highest supported floor score for any of the moves equivalent to this one
								double[] worstCousinValues = null;
								for(TreeEdge siblingEdge : children)
								{
									if ( siblingEdge.selectAs == edge )
									{
										double[] moveFloor = worstCompleteCousinValues(siblingEdge.jointPartialMove[roleIndex].move, roleIndex);
										
										if ( moveFloor != null )
										{
											if ( worstCousinValues == null || worstCousinValues[roleIndex] < moveFloor[roleIndex])
											{
												worstCousinValues = moveFloor;
											}
										}
									}
								}
								
								if ( worstCousinValues != null && (floorDeciderScore == null || floorDeciderScore[roleIndex] < worstCousinValues[roleIndex]) )
								{
									floorDeciderScore = worstCousinValues;
								}
							}
						}
						
						for(int i = 0; i < numRoles; i++)
						{
							averageValues[i] += cr.node.averageScores[i];
						}
					}
				}
				else
				{
					allImmediateChildrenComplete = false;
				}
			}
						
			for(int i = 0; i < numRoles; i++)
			{
				averageValues[i] /= numUniqueChildren;
			}
			
			//	Experimental for simultaneous turn games - complete if a majority of
			//	options win for the deciding role regardless of cousin states
//			if ( numPotentialDecidingRoleWins >= 2)//Math.max(2, numUniqueChildren/4) )
//			{
//				decidingRoleWin = true;
//			}
			
			if ( allImmediateChildrenComplete && !decidingRoleWin && isSimultaneousMove && decidingRoleIndex == 0 )
			{
				allChildrenComplete = true;
				
				//	If the best we can do from this node is no better than the supported floor we
				//	don't require all nephews to be complete to complete this node at the floor
				if ( !hasSiblings() || (floorDeciderScore != null && floorDeciderScore[roleIndex] + epsilon >= bestValues[roleIndex]) )
				{
					//	There was only one opponent choice so this is not after all
					//	incomplete information, so complete with the best choice for
					//	the decider
					decidingRoleWin = true;
				}
				else
				{
					//	To auto complete with simultaneous turn and no deciding role win
					//	we require that all nephews be complete or that all alternatives
					//	are anyway equivalent
					boolean allNephewsComplete = allNephewsComplete();
					
					for(int i = 0; i < numRoles; i++)
					{
						if ( Math.abs(averageValues[i] - bestValues[i]) > epsilon )
						{
							allImmediateChildrenComplete = allNephewsComplete;
							
							break;
						}
					}
				}
				
				if ( allImmediateChildrenComplete && checkConsequentialSiblingCompletion )
				{
					checkSiblingCompletion(floorDeciderScore);
				}
			}
			
			if ( allImmediateChildrenComplete || decidingRoleWin )
			{
				//	Opponent's choice which child to take, so take their
				//	best value and crystalize as our value.   However, if it's simultaneous
				//	move complete with the average score since
				//	opponents cannot make the pessimal (for us) choice reliably
				if (isSimultaneousMove && !decidingRoleWin && decidingRoleIndex == 0)
				{				
					if ( floorDeciderScore != null && floorDeciderScore[roleIndex] > worstDeciderScore[roleIndex] )
					{
						worstDeciderScore = floorDeciderScore;
					}
					markComplete(worstDeciderScore);
					//markComplete(averageValues);
				}
				else
				{
					markComplete(bestValues);					
				}
			}
			
			mostLikelyWinner = -1;
		}
		
		public void reset(boolean freed)
		{
			numVisits = 0;
			numUpdates = 0;
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
			trimCount = 0;
			leastLikelyWinner = -1;
			mostLikelyWinner = -1;
			complete = false;
			allChildrenComplete = false;
			numChoices = null;
			seq = -1;
		}
		
		private TreeNodeRef getRef()
		{
			return new TreeNodeRef(this);
		}
		
		private void validate(boolean recursive)
		{
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
								System.out.println("Missing parent link");
							}
							if ( cr.node.complete && cr.node.averageScores[decidingRoleIndex] > 99.5 && !complete && !completedNodeQueue.contains(cr.node) )
							{
								System.out.println("Completeness constraint violation");
							}
							if ( (cr.node.decidingRoleIndex) == decidingRoleIndex && !isPuzzle )
							{
								System.out.println("Descendant type error");
							}
							
							if ( recursive )
							{
								cr.node.validate(true);
							}
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
			
			if (parents.size() > 0)
			{
				int numInwardVisits = 0;
				
				for(TreeNode parent : parents)
				{
					for(TreeEdge edge : parent.children)
					{
						if ( edge.child.node == this )
						{
							numInwardVisits += edge.numChildVisits;
							break;
						}
					}
				}
				
				if ( numInwardVisits > numVisits )
				{
					System.out.println("Linkage counts do not add up");
				}
			}
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
										//edge.child.node.numVisits = (edge.child.node.numVisits*numRemainingParents + numRemainingParents)/(numRemainingParents+1);
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
				
				//leastLikely.adjustDescendantCounts(-1);
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
				            if ( edge.numChildVisits == 0 )
				            {
				            	uctValue = -1000;
				            }
				            else
				            {
				            	uctValue = -explorationUCT(edge.numChildVisits, roleIndex) - exploitationUCT(edge, roleIndex);
				            	//uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[leastLikelyWinner])+1) / numChildVisits[leastLikelyWinner]);
				            }
				            uctValue /= Math.log(Math.max(1, c.numVisits+2-c.trimCount));	//	utcVal is negative so this makes larger subtrees score higher (less negative)
		        			
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
						            if ( edge.numChildVisits == 0 )
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
						            	uctValue = -explorationUCT(edge.numChildVisits, roleIndex) - exploitationUCT(edge, roleIndex);
						            	//uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[i])+1) / numChildVisits[i]);
						            }
						            uctValue /= Math.log(c.numVisits+2);	//	utcVal is negative so this makes larger subtrees score higher (less negative)
						            
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
	        	trimCount++;
		        //System.out.println("  selected: " + selected.state);
	        	return children[selectedIndex].child.node.selectLeastLikelyNode(children[selectedIndex], depth+1);
	        }
	        
	        if ( depth < 2 )
	        	System.out.println("Selected unlikely node at depth " + depth);
	        return this;
		}
		
	    public void selectAction() throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException, InterruptedException {
			ProfileSection methodSection = new ProfileSection("TreeNode.selectAction");
			try
			{
				//validateAll();
				completedNodeQueue.clear();
				
		        //List<TreeNode> visited = new LinkedList<TreeNode>();
				TreePath visited = new TreePath(this);
		        TreeNode cur = this;
		        TreePathElement selected = null;
		        //visited.add(this);
		        while (!cur.isUnexpanded()) {
		        	selected = cur.select(selected == null ? null : selected.getEdge());

		            cur = selected.getChildNode();
		            //visited.add(cur);
		            visited.push(selected);
		        }
		        
		        TreeNode newNode;
		        if ( !cur.complete )
		        {
		        	//	Expand for each role so we're back to our-move as we always rollout after joint moves
			        cur.expand(selected == null ? null : selected.getEdge());
			        
			        if ( !cur.complete )
			        {
				        selected = cur.select(selected == null ? null : selected.getEdge());
				        newNode = selected.getChildNode();
				        //visited.add(newNode);
				        visited.push(selected);
				        while ( newNode.decidingRoleIndex != numRoles-1 && !newNode.complete)
				        {
				        	newNode.expand(selected.getEdge());
				        	if ( !newNode.complete )
				        	{
				        		selected = newNode.select(selected.getEdge());
				        		newNode = selected.getChildNode();
				        		//visited.add(newNode);
						        visited.push(selected);
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
		        
		        //	Add a pseudo-edge that represents the link into the unexplored part of the tree
		        //visited.push(null);
				//validateAll();
		        //System.out.println("Rollout from: " + newNode.state);
		        RolloutRequest rollout = newNode.rollOut(visited);
		        if ( rollout != null )
		        {
		        	newNode.updateStats(rollout.averageScores, rollout.averageSquaredScores, rolloutSampleSize, visited, true);
		        }
		        else
		        {
		        	//for(TreeNode node : visited)
		        	//{
		        	//	node.validate(false);
		        	//}
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
		    		ForwardDeadReckonLegalMoveSet moves = underlyingStateMachine.getLegalMoves(state);
		    		List<ForwardDeadReckonLegalMoveInfo> moveInfos = new LinkedList<ForwardDeadReckonLegalMoveInfo>();

		    		for(ForwardDeadReckonLegalMoveInfo move : moves.getContents(choosingRole))
		    		{
		    			moveInfos.add(move);	
		    		}
		    		TreeEdge[] newChildren = new TreeEdge[moves.getContentSize(choosingRole)];
		    		
	    			int index = 0;
		    		if ( children != null )
		    		{
		    			for(TreeEdge edge : children)
		    			{
		    				TreeNode child = edge.child.node;
		    				if ( edge.child.seq == child.seq )
		    				{
		    					moveInfos.remove(edge.jointPartialMove[roleIndex]);
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
	    				newEdge.jointPartialMove[roleIndex] = moveInfos.remove(0);
	    				if ( roleIndex == numRoles-1 )
	    				{
			    			newState = underlyingStateMachine.getNextState(state, newEdge.jointPartialMove);
	    				}
	    				
    					newEdge.child = allocateNode(underlyingStateMachine, newState, this).getRef();
    					newEdge.selectAs = newEdge;
    					
    					//	Check for multiple moves that all transition to the same state
    					for(int i = 0; i < index; i++)
    					{
    						if ( newChildren[i] != null && newChildren[i].child.node == newEdge.child.node )
    						{
    							newEdge.selectAs = newChildren[i];
    							break;
    						}
    					}
    					
    					newChildren[index] = newEdge;
    					TreeNode newChild = newEdge.child.node;
    					
    					newChild.decidingRoleIndex = roleIndex;
    					
    					if ( numChoices == null )
    					{
    						numChoices = new int[numRoles];
							for(int i = 0; i < numRoles; i++)
							{
								numChoices[i] = 0;
							}
    					}
    					
    					if ( newState == null )
    					{
    						newChild.state = state;
    						newChild.numChoices = numChoices;
    					}
    					else
    					{
    						newChild.numChoices = new int[numRoles];
    						
    						if ( underlyingStateMachine.isTerminal(newState) )
    						{
    							newChild.isTerminal = true;
    							
    							for(int i = 0; i < numRoles; i++)
    							{
    								newChild.averageScores[i] = underlyingStateMachine.getGoal(roleIndexToRole(i));
    								newChild.averageSquaredScores[i] = 0;
    							}
    							
 								//	Add win bonus
    							for(int i = 0; i < numRoles; i++)
    							{
									double iScore = newChild.averageScores[i];
									bonusBuffer[i] = 0;
									
    								for(int j = 0; j < numRoles; j++)
    								{
    									if ( j != i )
    									{
	    									double jScore = newChild.averageScores[j];

	    									if ( iScore >= jScore )
	    									{
	    										double bonus = competitivenessBonus;
	    										
	    										if ( iScore > jScore )
	    										{
	    											bonus *= 2;
	    										}
	    										
	    										bonusBuffer[i] += bonus;
	    									}
    									}
    								}
    							}
    							
    							for(int i = 0; i < numRoles; i++)
    							{
    								newChild.averageScores[i] = ((newChild.averageScores[i] + bonusBuffer[i])*100)/(100+2*(numRoles-1)*competitivenessBonus);
    							}
    						}
    						else
    						{
 								for(int i = 0; i < numRoles; i++)
								{
									int num = underlyingStateMachine.getLegalMoves(newChild.state).getContentSize(reorderedRoles[i]);
									if ( num > 1 )
									{
										newChild.numChoices[i] = num;
									}
									else
									{
										newChild.numChoices[i] = numChoices[i];
									}
								}
							}
    					}
    					
    					if ( newChild.numVisits == 0 && heuristicSampleWeight > 0 && !newChild.isTerminal )
    					{
							double[] heuristicScores = heuristicStateValue(newChild.state, this);
							double heuristicSquaredDeviation = 0;
							
							//validateScoreVector(heuristicScores);
							
							for(int i = 0; i < numRoles; i++)
							{
								newChild.averageScores[i] = heuristicScores[i];
								heuristicSquaredDeviation += (root.averageScores[i]-heuristicScores[i])*(root.averageScores[i]-heuristicScores[i]);
							}
							
							if ( heuristicSquaredDeviation > 0.01 && root.numVisits > 50 )
							{
								newChild.numUpdates = heuristicSampleWeight;
								newChild.numVisits = heuristicSampleWeight;
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
						checkChildCompletion(true);
					}
		    		//validateAll();
		    	}
			}
			finally
			{
				methodSection.exitScope();
			}
	    }
	    
	    private void validateScoreVector(double[] scores)
	    {
	    	double total  = 0;
	    	
	    	for(int i = 0; i < numRoles; i++ )
	    	{
	    		total += scores[i];
	    	}
	    	
	    	if ( total > 0 && Math.abs(total-100) > epsilon)
	    	{
	    		System.out.println("Bad score vector");
	    	}
	    	
	    	if ( total > 0 && children != null )
	    	{
	    		total = 0;
	    		int visitTotal = 0;
	    		
	    		for(TreeEdge edge : children)
	    		{
	    			if ( edge == edge.selectAs)
	    			{
	    				total += edge.child.node.averageScores[0]*edge.numChildVisits;
	    				visitTotal += edge.numChildVisits;
	    			}
	    		}
	    		
	    		if ( visitTotal > 200 && Math.abs(averageScores[0] - total/visitTotal) > 10 )
	    		{
	    			System.out.println("Parent stats do not match chikdren");
	    		}
	    	}
	    }
	    
	    private int getNumChoices(ForwardDeadReckonInternalMachineState afterState, int forRole)
	    {
    		if ( numChoices[forRole] == 0 && children != null )
    		{
				int num = 0;
				int count = 0;
				//	Average over children
				for(TreeEdge edge : children)
				{
					int contribution = edge.child.node.getNumChoices(null, forRole);
					
					if ( contribution > 0 )
					{
						count++;
						num += contribution;
					}
				}
				
				if ( count > 0 )
				{
					numChoices[forRole] = (num+count/2)/count;
				}
    		}
    		
	    	if ( !state.equals(afterState) && decidingRoleIndex == forRole && numChoices != null && numChoices[forRole] > 1 )
	    	{
	    		return numChoices[forRole];
	    	}
	    	else if ( children != null )
	    	{
	    		return children[0].child.node.getNumChoices(afterState, forRole);
	    	}
	    	else
	    	{
	    		return 0;
	    	}
	    }
	    
	    private int getNumPieces(ForwardDeadReckonInternalMachineState afterState, int forRole)
	    {
	    	if ( !state.equals(afterState) && decidingRoleIndex == forRole && numChoices != null && numChoices[forRole] > 1 )
	    	{
	    		int result = pieceStateMaps[decidingRoleIndex].intersectionSize(state);
	    		if ( result > 12 )
	    		{
	    			result = pieceStateMaps[decidingRoleIndex].intersectionSize(state);
	    		}
	    		return result;
	    	}
	    	else if ( children != null )
	    	{
	    		return children[0].child.node.getNumPieces(afterState, forRole);
	    	}
	    	else
	    	{
	    		return -1;
	    	}
	    }
	    
	    private double heuristicValue(TreeEdge edge)
	    {
	    	return heuristicValue(edge, true);
	    }
		    
	    private double heuristicValue(TreeEdge edge, boolean normalize)
	    {
	    	double result = 0;
	    	
	    	//Action history contribution
	    	//ForwardDeadReckonLegalMoveInfo moveInfo = edge.jointPartialMove[edge.child.node.decidingRoleIndex];
	    	//result += masterMoveWeights.weightScore[moveInfo.globalMoveIndex]/100;
	    	
	    	//	Mobility heuristic contribution
	    	//double nextNumOpponentChoices = edge.child.node.getNumChoices(state, decidingRoleIndex);
	    	//double numOpponentChoices = numChoices[decidingRoleIndex];

	    	//if ( nextNumOpponentChoices != 0 )
	    	//{
	    	//	result += (numOpponentChoices/nextNumOpponentChoices);
	    	//}
	    	
	    	//	Piece count heuristic contribution
	    	//double nextNumOpponentPieces = edge.child.node.getNumPieces(state, decidingRoleIndex);
	    	//double numOpponentPieces = pieceStateMaps[decidingRoleIndex].intersectionSize(state);

	    	//if ( nextNumOpponentPieces != -1 )
	    	//{
	    	//	result += Math.max(0, numOpponentPieces - nextNumOpponentPieces)/4;
	    	//}
	    	
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
	    	
    		if ( normalize )
    		{
    			result = result/Math.log(edge.numChildVisits+2);
    		}
	    	
	    	return result;
	    }
	    
	    private double explorationUCT(int numChildVisits, int roleIndex)
	    {
	    	//	When we propagate adjustments due to completion we do not also adjust the variance contribution
	    	//	so this can result in 'impossibly' low (aka negative) variance - take a lower bound of 0
        	double varianceBound = Math.max(0, averageSquaredScores[roleIndex] - averageScores[roleIndex]*averageScores[roleIndex])/10000 + Math.sqrt(2*Math.log(Math.max(numVisits,numChildVisits)+1) / numChildVisits);
        	return explorationBias*Math.sqrt(2*Math.min(0.5,varianceBound)*Math.log(Math.max(numVisits,numChildVisits)+1) / numChildVisits)/roleRationality[roleIndex];
	    }
	    
	    private boolean allCousinsComplete(TreeEdge relativeTo)
	    {
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
    	    					if ( nephewEdge.jointPartialMove[relativeTo.child.node.decidingRoleIndex] == relativeTo.jointPartialMove[relativeTo.child.node.decidingRoleIndex] &&
    	    						 !nephewEdge.child.node.complete )
    	    					{
    	    						return false;
    	    					}
    	    				}
    	    			}
    				}
    			}
    		}
	    	return true;
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
	    					double thisSampleWeight = 0.1 + child.averageScores[child.decidingRoleIndex];//(child.averageScores[child.decidingRoleIndex]*(child.numVisits+1) + 50*Math.log(child.numVisits+1))/(child.numVisits+1);
	    					
	    	    			for(TreeEdge nephewEdge : child.children)
	    	    			{
	    	    				TreeNode nephew = nephewEdge.child.node;
	    	    				
	    	    				if ( nephewEdge.child.seq == nephew.seq )
	    	    				{
	    	    					Move move = nephewEdge.jointPartialMove[relativeTo.child.node.decidingRoleIndex].move;
			    					MoveScoreInfo accumulatedMoveInfo = cousinMoveCache.get(move);
			    					if ( accumulatedMoveInfo == null )
			    					{
			    						accumulatedMoveInfo = new MoveScoreInfo();
			    						cousinMoveCache.put(move, accumulatedMoveInfo);
			    					}
			    					
			    					if ( thisSampleWeight != 0 )
			    					{
				    					accumulatedMoveInfo.averageScore = (accumulatedMoveInfo.averageScore*accumulatedMoveInfo.sampleWeight + thisSampleWeight*nephew.averageScores[roleIndex])/(accumulatedMoveInfo.sampleWeight+thisSampleWeight);
				    					accumulatedMoveInfo.sampleWeight += thisSampleWeight;
			    					}
	    	    				}
	    	    			}
	    				}
	    			}
	    		}
	    	}
	    	
			MoveScoreInfo accumulatedMoveInfo = cousinMoveCache.get(relativeTo.jointPartialMove[relativeTo.child.node.decidingRoleIndex].move);
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
	    		if ( roleIndex == 0 )
	    		{
	    			return inboundEdge.child.node.averageScores[roleIndex]/100;
	    		}
	    		else
	    		{
	    			return Math.min(inboundEdge.child.node.averageScores[roleIndex]/100, getAverageCousinMoveValue(inboundEdge, roleIndex));
	    		}
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
	    		return inboundEdge.child.node.averageScores[roleIndex]/100;// + getAverageCousinMoveValue(inboundEdge, roleIndex)/Math.log(inboundEdge.child.node.numVisits+2);// + heuristicValue()/Math.log(numVisits+2);// + averageSquaredScore/20000;
	    	}
	    }

	    private TreePathElement select(TreeEdge from) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
	        TreeEdge selected = null;
	        int selectedIndex = -1;
	        double bestCompleteValue = Double.MIN_VALUE;
	        TreeNode bestCompleteNode = null;
	        double bestValue = Double.MIN_VALUE;
	        
    		//	Find the role this node is choosing for
    		int roleIndex = (decidingRoleIndex+1)%numRoles;
    		
			cousinMovesCachedFor = null;
	        //System.out.println("Select in " + state);
	        if ( trimmedChildren == 0 )
	        {		        
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
			        		//if ( cr.seq == c.seq && (!c.complete || (isSimultaneousMove && decidingRoleIndex == 0)))
				        	if ( cr.seq == c.seq && (!c.complete) && !c.allChildrenComplete)// || isMultiPlayer || (isSimultaneousMove && decidingRoleIndex == 0 && !allCousinsComplete(children[mostLikelyWinner]))))
			        		{
					            double uctValue;
					            
					            if ( c.numVisits == 0 && !c.complete )
					            {
						            // small random number to break ties randomly in unexpanded nodes
					            	uctValue = 1000 +  r.nextDouble() * epsilon + heuristicValue(children[mostLikelyWinner]);
					            }
					            else
					            {
					            	uctValue = explorationUCT(c.numVisits, roleIndex) + exploitationUCT(children[mostLikelyWinner], roleIndex) + heuristicValue(children[mostLikelyWinner]);
					            	//uctValue = explorationUCT(children[mostLikelyWinner].numChildVisits, roleIndex) + exploitationUCT(children[mostLikelyWinner], roleIndex) + heuristicValue(children[mostLikelyWinner]);
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
						            else if ( children[i].selectAs == children[i] )	//	Only select one move that is state-equivalent
						            {
							            double uctValue;
							            if ( c.numVisits == 0 && !c.complete )
							            {
								            // small random number to break ties randomly in unexpanded nodes
							            	uctValue = 1000 +  r.nextDouble() * epsilon + heuristicValue(children[i]);
							            }
							            else
							            {
							            	uctValue = (c.complete ? 0 : explorationUCT(c.numVisits, roleIndex)) + exploitationUCT(children[i], roleIndex) + heuristicValue(children[i]);
							            	//uctValue = explorationUCT(children[i].numChildVisits, roleIndex) + exploitationUCT(children[i], roleIndex) + heuristicValue(children[i]);
							            	//uctValue = c.averageScore/100 + Math.sqrt(Math.log(Math.max(numVisits,numChildVisits[i])+1) / numChildVisits[i]);
							            }

						            	//if ( !c.complete || (isSimultaneousMove && decidingRoleIndex == 0) )
								        if ( !c.complete && !c.allChildrenComplete)// || isMultiPlayer || (isSimultaneousMove && decidingRoleIndex == 0 && !allCousinsComplete(children[i])) )
							            {
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
							            else
							            {
							            	if (uctValue > bestCompleteValue)
							            	{
							            		bestCompleteValue = uctValue;
							            		bestCompleteNode = c;
							            	}
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
//	        	if ( trimmedChildren == 0 )
//	        	{
//	        		System.out.println("no selection found on untrimmed node!");
//	        		//select();
//	        		System.out.println("Terminality: " + underlyingStateMachine.isTerminal(state));
//	        	}
	        	//System.out.println("  select random");
	        	//	pick at random.  If we pick one that has been trimmed re-expand it
	        	//	FUTURE - can establish a bound on the trimmed UCT value to avoid
	        	//	randomization for a while at least
	        	int childIndex = r.nextInt(children.length);
	        	selected = children[childIndex];
	        	TreeNodeRef cr = selected.child;

	        	numSelectionsThroughIncompleteNodes++;
	        	
	        	if ( cr.seq != cr.node.seq )
	        	{
	        		numReExpansions++;
	        		
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
	        
	        TreePathElement result = new TreePathElement(selected);
	        //if ( bestCompleteNode != null && bestCompleteNode.averageScores[roleIndex] > selected.child.node.averageScores[roleIndex] )
		    if ( bestCompleteNode != null && bestCompleteValue > bestValue )
	        {
	        	result.setScoreOverrides(bestCompleteNode.averageScores);
	        	bestCompleteNode.numVisits++;
	        	mostLikelyWinner = -1;
	        }
	        
//        	if ( parents.contains(root))
//        	{
//        		System.out.println("Select through response " + result.getEdge().jointPartialMove[1].move + " with score " + result.getChildNode().averageScores[1] + " (uct " + bestValue + ")");
//        		if ( counter++ > 5000 )
//        		{
//        			System.out.println("!");
//        		}
//        	}

	        return result;
	    }
	    
	    private int counter = 0;

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
	    		return from.averageScores[forRoleIndex];	//	TEMP TEMP TEMP
	    	}
	    	
	    	double result = 0;
	    	double childResult = -Double.MAX_VALUE;
	    	
	    	for(TreeEdge edge : children)
	    	{
	    		if ( edge.child.seq == edge.child.node.seq )
	    		{
	    			double childVal = edge.child.node.averageScores[edge.child.node.decidingRoleIndex];
	    			
	    			if ( childVal > childResult )//&& edge.child.node.numVisits > 500 )
	    			{
	    				childResult = childVal;
		    			result = edge.child.node.scoreForMostLikelyResponseRecursive(this, forRoleIndex);
	    			}
	    		}
	    	}
	    	
	    	return (childResult == -Double.MAX_VALUE ? averageScores[forRoleIndex] : Math.min(result,averageScores[forRoleIndex]));
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
						System.out.println("    Response " + edge2.jointPartialMove[edge2.child.node.decidingRoleIndex].move + " scores " + edge2.child.node.stringizeScoreVector() + ", visits " + edge2.child.node.numVisits + ", seq : " + edge2.child.seq + (edge2.child.node.complete ? " (complete)" : ""));
					}
				}    		
	    	}
	    	else
	    	{
	    		children[0].child.node.traceFirstChoiceNode();
	    	}
	    }
	    
	    private void indentedPrint(PrintWriter writer, int depth, String line)
	    {
	    	StringBuilder indent = new StringBuilder();
	    	
	    	for(int i = 0; i < depth; i++)
	    	{
	    		indent.append(" ");
	    	}
	    	writer.println(indent + line);
	    }
	    
	    private void dumpTree(PrintWriter writer, int depth, TreeEdge arrivalPath)
	    {
	    	if ( arrivalPath == null )
	    	{
	    		indentedPrint(writer, depth*2, "Root scores " + stringizeScoreVector());
	    	}
	    	else
	    	{
	    		indentedPrint(writer, depth*2, "@" + depth + ": Move " + arrivalPath.jointPartialMove[decidingRoleIndex].move + " scores " + stringizeScoreVector() + (complete ? " (complete)" : "") + " - visits: " + numVisits + " (" + arrivalPath.numChildVisits + ")");
	    	}
	    	
	    	if ( sweepSeq == sweepInstance )
	    	{
	    		indentedPrint(writer, (depth+1)*2, "...transition...");
	    	}
	    	else
	    	{
	    		sweepSeq = sweepInstance;
	    		
		    	if ( children != null )
		    	{
		    		for(TreeEdge edge : children)
		    		{
		    			if ( edge.child.node.seq == edge.child.seq )
		    			{
		    				edge.child.node.dumpTree(writer, depth+1, edge);
		    			}
		    		}
		    	}
	    	}
	    }
	    
	    private void dumpTree(String filename)
	    {
	    	sweepInstance++;
	    	
	        try {
	            File f = new File(filename);
	            PrintWriter writer = new PrintWriter(f);
	            dumpTree(writer, 0, null);
	            writer.close();
	        } catch(Exception e) {
	            GamerLogger.logStackTrace("StateMachine", e);
	        }
	    }
	    
	    private void postProcessResponseCompletion()
	    {
	    	if ( children != null )
	    	{
				if ( children.length > 1 )
		    	{
					if ( decidingRoleIndex != (numRoles-1)%numRoles )
					{
						for(TreeEdge edge2 : children)
						{
							if ( edge2.child.seq == edge2.child.node.seq )
							{
		    	    			if ( edge2.child.node.averageScores[0] <= lowestRolloutScoreSeen && edge2.child.node.complete )
		    	    			{
		    	    				System.out.println("Post-processing completion of response node");
	    	    					markComplete(edge2.child.node.averageScores);
	    	    					processCompletion();
		    	    			}
							}
						}
					}    		
		    	}
		    	else
		    	{
		    		children[0].child.node.postProcessResponseCompletion();
		    	}
	    	}
	    }
	    
	    public Move getBestMove(boolean traceResponses)
	    {
	    	double bestScore = -Double.MAX_VALUE;
	    	double bestRawScore = -Double.MAX_VALUE;
	    	int mostSelected = -Integer.MAX_VALUE;
	    	Move rawResult = null;
	    	Move result = null;
	    	boolean anyComplete = false;
	    	TreeNode bestNode = null;
	    	
	    	for(TreeEdge edge : children)
	    	{
	    		if ( edge.child.node.complete )
	    		{
	    			anyComplete = true;
	    		}
	    		else if ( edge.child.node.children != null && lowestRolloutScoreSeen < 100 && !isMultiPlayer && !isSimultaneousMove)
	    		{
	    	    	//	Post-process completions of children with respect the the observed rollout score range
	    			edge.child.node.postProcessResponseCompletion();
	    		}
	    	}
	    	
		    for(TreeEdge edge : children)
		    {
	    		TreeNode child = edge.child.node;
	    		
	    		double moveScore = (isSimultaneousMove || isMultiPlayer || anyComplete || disableOnelevelMinimax) ? child.averageScores[0] : child.scoreForMostLikelyResponse();
    			//	If we have complete nodes with equal scores choose the one with the highest variance
	    		if ( edge.child.node.complete )
	    		{
	    			double varianceMeasure = child.averageSquaredScores[0]/100;

	    			if ( moveScore < 0.1 )
	    			{
	    				moveScore = varianceMeasure - 100;
	    			}
	    		}
	    		System.out.println("Move " + edge.jointPartialMove[0].move + " scores " + moveScore + " (raw score " + child.averageScores[0] + ", selection count " + child.numVisits + ", seq " + child.seq + (child.complete ? ", complete" : "") + ")");
    			if (child.children != null && !child.complete && traceResponses)
    			{
    				child.traceFirstChoiceNode();
    			}
    			//	Don't accept a complete score which no rollout has seen worse than, if there is
    			//	any alternative
    			if ( bestNode != null && !bestNode.complete && child.complete && moveScore <= lowestRolloutScoreSeen && lowestRolloutScoreSeen < 100 )
    			{
    				continue;
    			}
	    		if ( moveScore > bestScore ||
	    			 (moveScore == bestScore && child.complete && (child.numVisits > mostSelected || !bestNode.complete)) ||
	    			 (bestNode != null && bestNode.complete && !child.complete && bestNode.averageScores[0] <= lowestRolloutScoreSeen && lowestRolloutScoreSeen < 100))
	    		{
	    			bestNode = child;
	    			bestScore = moveScore;
	    			mostSelected = child.numVisits;
	    			result = edge.jointPartialMove[0].move;
	    		}
	    		if ( child.averageScores[0] > bestRawScore || (child.averageScores[0] == bestRawScore && child.complete && child.averageScores[0] > 0))
	    		{
	    			bestRawScore = child.averageScores[0];
	    			rawResult = edge.jointPartialMove[0].move;
	    		}
	    	}
		    
		    //dumpTree("C:\\temp\\mctsTree.txt");
	    	
	    	if ( result == null )
	    	{
	    		System.out.println("No move found!");
	    	}
	    	if ( rawResult != result )
	    	{
	    		System.out.println("1 level minimax result differed from best raw move: " + rawResult);
	    	}
	    	return result;
	    }

	    public RolloutRequest rollOut(TreePath path) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException, InterruptedException
	    {
        	RolloutRequest request = new RolloutRequest();
        	
	        if ( complete )
	        {
	        	//System.out.println("Terminal state " + state + " score " + averageScore);
	        	numTerminalRollouts++;
	        	
	        	for(int i = 0; i < numRoles; i++)
	        	{
	        		request.averageScores[i] = averageScores[i];
	        		request.averageSquaredScores[i] = averageSquaredScores[i];
	        	}
	        	
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
	        	//request.moveWeights = masterMoveWeights.copy();
	        	
	        	numQueuedRollouts++;
	        	queuedRollouts.put(request);
	        	
	        	return null;
	        }
	    }

	    public void updateVisitCounts(int sampleSize, TreePath path)
	    {
	    	TreePathElement element = path.getCurrentElement();
	    	TreeEdge childEdge = (element == null ? null : element.getEdge());

	    	numVisits++;// += sampleSize;
	    	
	    	//for(TreeNode parent : parents)
	    	//{
	    	//	if ( !parent.complete || isSimultaneousMove || isMultiPlayer )
	    	//	{
	    	//		parent.updateVisitCounts(sampleSize, path);
	    	//	}
	    	//}
	    	
	    	if ( childEdge != null )
	    	{
	    		childEdge.numChildVisits++;
	    				
				if ( childEdge.numChildVisits > childEdge.child.node.numVisits )
				{
					System.out.println("Edge count greater than target visit count");
				}
	    	}
	    	
	    	if ( path.hasMore() )
	    	{
				TreeNode node = path.getNextNode();
				if ( node != null )
				{
					node.updateVisitCounts(sampleSize, path);
				}
	    	}
	    }
	   
	    private int dumpCount = 0;
	    double lastDebugNodeScore = 100;
	    
	    public void updateStats(double[] values, double[] squaredValues, int sampleSize, TreePath path, boolean isCompletePseudoRollout)
	    {
	    	TreePathElement element = path.getCurrentElement();
	    	TreeEdge childEdge = (element == null ? null : element.getEdge());

			double[]	oldAverageScores = new double[numRoles];
			double[]	oldAverageSquaredScores = new double[numRoles];
			boolean		visitCountsUpdated = false;
			
			double[]	overrides = (element == null ? null : element.getScoreOverrides());
			if ( overrides != null )
			{
				values = overrides;
			}
			
			//validateScoreVector(averageScores);
			//validateScoreVector(values);
//			boolean debugNode = false;
//			if ( parents.contains(root))
//			{
//				for(TreeEdge rootChild : root.children)
//				{
//					if ( rootChild.child.node == this )
//					{
//						if ( rootChild.jointPartialMove[0].toString().contains("6"))
//						{
//							System.out.println("drop 6 update values: " + values[0] + (values == overrides ? " (override)" : ""));
//							System.out.println("...this node values currently: " + averageScores[0]);
//							if ( ++dumpCount == 200 )
//							{
//								System.out.println("!");
//							}
//							debugNode = true;
//							
//							if ( averageScores[0] > lastDebugNodeScore )
//							{
//								System.out.println("Scores rose unexpectedly!");
//							}
//							break;
//						}
//					}
//				}
//			}
			for(int roleIndex = 0; roleIndex < numRoles; roleIndex++)
			{
		    	oldAverageScores[roleIndex] = averageScores[roleIndex];
		    	oldAverageSquaredScores[roleIndex] = averageSquaredScores[roleIndex];
		    	
		    	if ( (!complete || isSimultaneousMove || isMultiPlayer) && childEdge != null )
		    	{
    				//	Increment child visit count if required before calculating as child's own numVisits
    				//	will have already been incremented in the previous stage of the recursion
//				    	if ( updateVisitCounts )
//				    	{
//				    		childEdge.numChildVisits++;
//				    	}
			    	
    				int numChildVisits = childEdge.numChildVisits;
    				
    				if ( numChildVisits > childEdge.child.node.numVisits)
    				{
    					System.out.println("Unexpected edge strength greater than total child strength");
    				}
    				//	Propagate a value that is a blend of this rollout value and the current score for the child node
    				//	being propagated from, according to how much of that child's value was accrued through this path
    				//	Note - we disable this for simultaneous turn games since it empirically causes issues there, probably
    				//	due to introducing incorrect biases over choices between moves with imperfect information
    				if ( /*!isSimultaneousMove &&*/ values != overrides )
    				{
//    					if ( debugNode && isCompletePseudoRollout && dumpCount > 100 )
//    					{
//    						System.out.println("!");
//    					}
    					values[roleIndex] = (values[roleIndex]*numChildVisits + childEdge.child.node.averageScores[roleIndex]*(childEdge.child.node.numVisits - numChildVisits))/childEdge.child.node.numVisits;
    				}
		    	}
		    	
//		    	if ( isCompletePseudoRollout)
//		    	{
//		    		averageScores[roleIndex] = (averageScores[roleIndex]*numVisits + values[roleIndex])/(numVisits+1);
//		    		averageSquaredScores[roleIndex] = (averageSquaredScores[roleIndex]*numVisits + squaredValues[roleIndex])/(numVisits+1);
//			    	
//		    		//numVisits++;
//		    	}
//		    	else
		    	{
//		    		if ( numVisits == 0 )
//		    		{
//		    			System.out.println("Updating stats for unvisited node");
//		    		}
	
		    		if ( !complete )
		    		{
			    		averageScores[roleIndex] = (averageScores[roleIndex]*numUpdates + values[roleIndex])/(numUpdates+1);
			    		averageSquaredScores[roleIndex] = (averageSquaredScores[roleIndex]*numUpdates + squaredValues[roleIndex])/(numUpdates+1);
		    		}
		    	}
//				if ( debugNode && dumpCount > 100 )
//				{
//					if (averageScores[0] > lastDebugNodeScore )
//					{
//						System.out.println("Scores rose unexpectedly!");
//					}
//					lastDebugNodeScore = averageScores[0];
//				}
		    	
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
				
//				if ( isCompletePseudoRollout && !visitCountsUpdated )
//				{
//					numVisits++;
//					
//			    	if ( (!complete || isSimultaneousMove || isMultiPlayer) && childEdge != null )
//			    	{
//			    		childEdge.numChildVisits++;
//			    	}
//			    	
//			    	visitCountsUpdated = true;
//				}
			}
		
			if ( isCompletePseudoRollout )
			{
				numVisits++;
				
		    	if ( (!complete || isSimultaneousMove || isMultiPlayer) && childEdge != null )
		    	{
		    		childEdge.numChildVisits++;
		    	}
			}
			
			//validateScoreVector(averageScores);
			numUpdates++;
  	
			if ( path.hasMore() )
			{
				TreeNode node = path.getNextNode();
				if ( node != null )
				{
					node.updateStats(values, squaredValues, sampleSize, path, isCompletePseudoRollout);
				}
			}
    	}
	}
	
	private void validateAll()
	{
		if ( root != null )
			root.validate(true);
		
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
		numIncompleteNodes = 0;
	}
	
	private TestForwardDeadReckonPropnetStateMachine underlyingStateMachine;
	private volatile TreeNode root = null;
		
	@Override
	public String getName() {
		return "Sancho 1.50";
	}
	
	@Override
	public StateMachine getInitialStateMachine()
	{
		if ( searchProcessor == null )
		{
			searchProcessor = new TreeSearcher();
			(new Thread(searchProcessor)).start();
		}
		else
		{
			try {
				System.out.println("Stop search processor...");
				searchProcessor.stop();
				System.out.println("...stopped");
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
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
	    
		numQueuedRollouts = 0;
		numCompletedRollouts = 0;
		queuedRollouts.clear();
		completedRollouts.clear();
		
		//GamerLogger.setFileToDisplay("StateMachine");
		//ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
		underlyingStateMachine = new TestForwardDeadReckonPropnetStateMachine(1+numRolloutThreads, getRoleName());
		
		emptyTree();
		System.gc();

		return new StateMachineProxy(underlyingStateMachine);
	}
	
	private final boolean disableOnelevelMinimax = true;//false;
	private boolean isPuzzle = false;
	private boolean isMultiPlayer = false;
	private boolean isSimultaneousMove = false;
	private boolean isPseudoSimultaneousMove = false;
	private boolean isIteratedGame = false;
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
	
	private ForwardDeadReckonInternalMachineState[] pieceStateMaps = null;
	
	@Override
	public void stateMachineMetaGame(long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		ourRole = getRole();
		
		numRoles = underlyingStateMachine.getRoles().size();
		bonusBuffer = new double[numRoles];
		rootPieceCounts = new int[numRoles];
		heuristicStateValueBuffer = new double[numRoles];
	    roleRationality = new double[numRoles];

		pieceStateMaps = null;
		
		initializeRoleOrdering();
		
		isPuzzle = (numRoles == 1);
		isMultiPlayer = (numRoles > 2);
		
		MinRawNetScore = 0;
		MaxRawNetScore = 100;
	    underExpectedRangeScoreReported = false;
	    overExpectedRangeScoreReported = false;
	    completeSelectionFromIncompleteParentWarned = false;
	    
	    AStarSolutionPath = null;
	    AStarFringe = null;
	    targetStateAsInternal = null;
		
	    if ( rolloutProcessors == null && numRolloutThreads > 0 )
	    {
	    	rolloutProcessors = new RolloutProcessor[numRolloutThreads];
	    	
	    	for(int i = 0; i < numRolloutThreads; i++)
	    	{
	    		rolloutProcessors[i] = new RolloutProcessor(underlyingStateMachine.createInstance());
	    		rolloutProcessors[i].start();
	    	}
	    }	    
		
	    //	For now assume players in muli-player games are somewhat irrational.
	    //	FUTURE - adjust during the game based on correlations with expected
	    //	scores
	    for(int i = 0; i < numRoles; i++)
	    {
		    if ( isMultiPlayer )
		    {
		    	roleRationality[i] = (i == 0 ? 1 : 0.8);
		    }
		    else
		    {
		    	roleRationality[i] = 1;
		    }
	    }
	    
		int observedMinNetScore = Integer.MAX_VALUE;
		int observedMaxNetScore = Integer.MIN_VALUE;
		int simulationsPerformed = 0;
		int multiRoleSamples = 0;
		isPseudoSimultaneousMove = false;	
		boolean greedyRolloutsDisabled = false;

		targetState = null;

		multiRoleAverageScoreDiff = 0;
		
		Set<Analyser> analysers = new HashSet<Analyser>();
		PieceHeuristicAnalyser pieceSetAnalyser = new PieceHeuristicAnalyser();
		
		analysers.add(pieceSetAnalyser);
		for(Analyser analyser : analysers)
		{
			analyser.init(underlyingStateMachine);
		}
		
		ForwardDeadReckonInternalMachineState initialState = underlyingStateMachine.createInternalState(getCurrentState());
		
		//	Sample to see if multiple roles have multiple moves available
		//	implying this must be a simultaneous move game
		//	HACK - actually only count games where both players can play the
		//	SAME move - this gets blocker but doesn't include fully factored
		//	games like C4-simultaneous or Chinook (but it's a hack!)
		isSimultaneousMove = false;
		
		//	Also monitor whether any given player always has the SAME choice of move (or just a single choice)
		//	every turn - such games are (highly probably) iterated games
		List<Set<Move>> roleMoves = new ArrayList<Set<Move>>();
		isIteratedGame = true;
		
		for(int i = 0; i < numRoles; i++)
		{
			roleMoves.add(null);
		}
		
		double branchingFactorApproximation = 0;
		
		//	Perform a small number of move-by-move simulations to assess how
		//	the potential piece count heuristics behave at the granularity of
		//	a single decision
		for(int iteration = 0; iteration < 50; iteration++ )
		{
			ForwardDeadReckonInternalMachineState sampleState = new ForwardDeadReckonInternalMachineState(initialState);
			
			int numRoleMovesSimulated = 0;
			int numBranchesTaken = 0;
			
			while(!underlyingStateMachine.isTerminal(sampleState))
			{
				boolean	roleWithChoiceSeen = false;
				Move[] jointMove = new Move[numRoles];
				Set<Move> allMovesInState = new HashSet<Move>();
		    	
				int choosingRoleIndex = -1;
				for(int i = 0; i < numRoles; i++)
				{
					List<Move> legalMoves = underlyingStateMachine.getLegalMoves(sampleState, roleIndexToRole(i));
					
					if ( legalMoves.size() > 1 )
					{
						Set<Move> previousChoices = roleMoves.get(i);
						HashSet<Move> moveSet = new HashSet<Move>(legalMoves);

						if ( previousChoices != null && !previousChoices.equals(moveSet))
						{
							isIteratedGame = false;
						}
						else
						{
							roleMoves.set(i, moveSet);
						}

						choosingRoleIndex = i;
						for(Move move : legalMoves)
						{
							if ( allMovesInState.contains(move) )
							{
								isSimultaneousMove = true;
								choosingRoleIndex = -1;
								break;
							}
							else
							{
								allMovesInState.add(move);
							}
						}
						
						if ( roleWithChoiceSeen )
						{
							isPseudoSimultaneousMove = true;
							choosingRoleIndex = -1;
						}
						
						roleWithChoiceSeen = true;
						
						numBranchesTaken += legalMoves.size();
						numRoleMovesSimulated++;
					}
					jointMove[roleIndexToRawRoleIndex(i)] = legalMoves.get(r.nextInt(legalMoves.size()));
				}
				
				for(Analyser analyser : analysers)
				{
					analyser.accrueInterimStateSample(sampleState, choosingRoleIndex);
				}
				
				sampleState = underlyingStateMachine.getNextState(sampleState, jointMove);
			}
			
			branchingFactorApproximation += (double)(numBranchesTaken/numRoleMovesSimulated);
		}
		
		branchingFactorApproximation /= 50;
    	    	
		if ( isIteratedGame )
		{
			System.out.println("May be an iterated game");
		}
 
		if ( isSimultaneousMove )
		{
			System.out.println("Game is a simultaneous turn game");
		}
		else if ( isPseudoSimultaneousMove )
		{
			System.out.println("Game is pseudo-simultaneous (factorizable?)");
		}
		else
		{
			System.out.println("Game is not a simultaneous turn game");
		}
		
		if ( isSimultaneousMove || isPseudoSimultaneousMove )
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
		long simulationStartTime = System.currentTimeMillis();
		long simulationStopTime = Math.min(timeout - 5000, simulationStartTime + 10000);
		
		int[] rolloutStats = new int[2];
		int maxNumTurns = 0;
		int minNumTurns = Integer.MAX_VALUE;
		double averageBranchingFactor = 0;
		double averageNumTurns = 0;
		double averageSquaredNumTurns = 0;
    	double[] roleScores = new double[numRoles];
	    
		while(System.currentTimeMillis() < simulationStopTime)
		{
			simulationsPerformed++;
			
			underlyingStateMachine.getDepthChargeResult(initialState, getRole(), rolloutStats, null, null);
			
	    	int netScore = netScore(underlyingStateMachine, null);
	    	
	    	for(int i = 0; i < numRoles; i++)
    	    {
	    		roleScores[i] = underlyingStateMachine.getGoal(roleIndexToRole(i));
    	    	
    	    	if ( i != 0 && isMultiPlayer )
    	    	{
    	    		//	If there are several enemy players involved extract a measure
    	    		//	of their goal correlation
    	    		for(Role role2 : underlyingStateMachine.getRoles())
    	    		{
    	    			if ( !role2.equals(ourRole) && !role2.equals(roleIndexToRole(i)) )
    	    			{
    	    				int role2Score = underlyingStateMachine.getGoal(role2);
    	    				
    	    				multiRoleSamples++;
    	    				multiRoleAverageScoreDiff += Math.abs(role2Score - roleScores[i]);
    	    			}
    	    		}
    	    	}
    	    }
	    	
	    	ForwardDeadReckonInternalMachineState finalState = underlyingStateMachine.getCurrentState();
	    	
			for(Analyser analyser : analysers)
			{
				analyser.accrueTerminalStateSample(finalState, roleScores);
			}
	    	
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
		}
		
		for(Analyser analyser : analysers)
		{
			analyser.completeAnalysis();
		}
		
		System.out.println("branchingFactorApproximation = " + branchingFactorApproximation + ", averageBranchingFactor = " + averageBranchingFactor);
		//	Massive hack - assume that a game longer than 30 turns is not really an iterated game unless it's of fixed length
		if ( isIteratedGame && (Math.abs(branchingFactorApproximation - averageBranchingFactor) > 0.1 || (maxNumTurns > 30 && maxNumTurns != minNumTurns)))
		{
			System.out.println("Game is not an iterated game");
			isIteratedGame = false;
		}
		
		if ( isIteratedGame )
		{
			System.out.println("Game is an iterated game");
		}
		
    	pieceStateMaps = pieceSetAnalyser.getPieceSets();
		
	    double stdDevNumTurns = Math.sqrt(averageSquaredNumTurns - averageNumTurns*averageNumTurns);
	    
		System.out.println("Range of lengths of sample games seen: [" + minNumTurns + "," + maxNumTurns + "], branching factor: " + averageBranchingFactor);
		System.out.println("Average num turns: " + averageNumTurns);
		System.out.println("Std deviation num turns: " + stdDevNumTurns);
		
		explorationBias = 18/(averageNumTurns + ((maxNumTurns+minNumTurns)/2 - averageNumTurns)*stdDevNumTurns/averageNumTurns) + 0.4;
		if ( explorationBias < 0.5 )
		{
			explorationBias = 0.5;
		}
		else if ( explorationBias > 1.2 )
		{
			explorationBias = 1.2;
		}

		//explorationBias /= 1.5;
    	if ( pieceStateMaps != null )
    	{
    		//	Empirically games with piece count heuristics seem to like lower
    		//	exploration bias - not entirely sure why!
    		explorationBias = explorationBias*0.6;
    	}

    	minExplorationBias = explorationBias*0.8;
    	maxExplorationBias = explorationBias*1.2;
    	
    	System.out.println("Set explorationBias range to [" + minExplorationBias + ", " + maxExplorationBias + "]");
		
		if( underlyingStateMachine.numRolloutDecisionNodeExpansions > 0)
		{
			System.out.println("Greedy rollout terminal discovery effectiveness: " + (underlyingStateMachine.greedyRolloutEffectiveness*100)/underlyingStateMachine.numRolloutDecisionNodeExpansions);
			System.out.println("Num terminal props seen: " + underlyingStateMachine.getNumTerminatingMoveProps() + " out of " + underlyingStateMachine.getBasePropositions().size());
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
		
		double greedyRolloutCost = (underlyingStateMachine.numRolloutDecisionNodeExpansions == 0 ? 0 : averageBranchingFactor*(1 - underlyingStateMachine.greedyRolloutEffectiveness/(underlyingStateMachine.numRolloutDecisionNodeExpansions)));
		
		System.out.println("Estimated greedy rollout cost: " + greedyRolloutCost);
		if ( minNumTurns == maxNumTurns ||
			 ((greedyRolloutCost > 8 || stdDevNumTurns < 0.15*averageNumTurns || underlyingStateMachine.greedyRolloutEffectiveness < underlyingStateMachine.numRolloutDecisionNodeExpansions/3) &&
			  !isPuzzle) )
		{
			if ( !greedyRolloutsDisabled )
			{
				greedyRolloutsDisabled = true;
				disableGreedyRollouts();
			    
			    //	Scale up the estimate of simulation rate since we'll be running without the overhead
			    //	of greedy rollouts (which is proportional to the branching factor)
			    simulationsPerformed *= (1+greedyRolloutCost);
			}
		}
		
		//	Special case handling for puzzles with hard-to-find wins
		//	WEAKEN THIS WHEN WE HAVE TRIAL A*
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
				
				int targetStateSize = targetState.getContents().size();
				
				if ( targetStateSize < Math.max(2, initialState.size()/2) )
				{
					System.out.println("Unsuitable target state based on state elimination - ignoring");
					targetState = null;
				}
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
			rolloutSampleSize = (int) (simulationsPerformed/(4*(simulationStopTime - simulationStartTime)) + 1);
			if ( rolloutSampleSize > 100)
			{
				rolloutSampleSize = 100;
			}
		}
		
		System.out.println(simulationsPerformed*1000/(simulationStopTime - simulationStartTime) + " simulations/second performed - setting rollout sample size to " + rolloutSampleSize);
		
		if ( ProfilerContext.getContext() != null )
		{
			GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
		}
		
	    numNonTerminalRollouts = 0;
	    numTerminalRollouts = 0;
	    
		if ( (!isIteratedGame || numRoles != 2) && targetState == null )
		{
			root = allocateNode(underlyingStateMachine, initialState, null);
			root.decidingRoleIndex = numRoles-1;
			
			searchProcessor.StartSearch(System.currentTimeMillis() + 60000, new ForwardDeadReckonInternalMachineState(initialState));
			
			try {
				Thread.sleep(Math.max(timeout - 3000 - System.currentTimeMillis(), 0));
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
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
	
	private class AStarNode implements Comparable<AStarNode>
	{
		private ForwardDeadReckonInternalMachineState	state;
		private AStarNode		parent;
		private Move			move;
		private int				pathLength;
		private int				heuristicCost = -1;
		
		public AStarNode(ForwardDeadReckonInternalMachineState state, AStarNode parent, Move move)
		{
			this.state = state;
			this.parent = parent;
			this.move = move;
			
			pathLength = (parent == null ? 0 : parent.pathLength+1);
		}
		
		public AStarNode getParent()
		{
			return parent;
		}
		
		public Move getMove()
		{
			return move;
		}
		
		public ForwardDeadReckonInternalMachineState getState()
		{
			return state;
		}
		
		public int getPriority()
		{
			return pathLength + heuristicCost();
		}
		
		private int heuristicCost()
		{
			return 0;
//			if ( heuristicCost == -1 )
//			{
//				ForwardDeadReckonInternalMachineState temp = new ForwardDeadReckonInternalMachineState(state);
//				
//				temp.intersect(targetStateAsInternal);
//				
//				heuristicCost = targetStateAsInternal.size() - temp.size();
//			}
//			
//			return heuristicCost;
		}

		@Override
		public int compareTo(AStarNode o)
		{
			return getPriority() - o.getPriority();
		}
	}
	
	private PriorityQueue<AStarNode> AStarFringe = null;
	private ForwardDeadReckonInternalMachineState targetStateAsInternal = null;
	private List<Move> AStarSolutionPath = null;
	ForwardDeadReckonInternalMachineState stepStateMask = null;
	
	private Move selectAStarMove(List<Move> moves, long timeout) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	{
		Move bestMove = moves.get(0);
		int[] numAtDistance = new int[50];
		
		for(int i = 0; i < 50; i++)
		{
			numAtDistance[i] = 0;
		}
		
		if ( AStarSolutionPath == null )
		{
			if ( AStarFringe == null )
			{
				AStarFringe = new PriorityQueue<AStarNode>();
				
				AStarFringe.add(new AStarNode(underlyingStateMachine.createInternalState(getCurrentState()), null, null));
			}
			
			stepStateMask = new ForwardDeadReckonInternalMachineState(underlyingStateMachine.getInfoSet());
			
			stepStateMask.clear();
			for(ForwardDeadReckonPropositionCrossReferenceInfo info : underlyingStateMachine.getInfoSet())
			{
				if ( info.sentence.toString().contains("step") )
				{
					stepStateMask.add(info);
				}
			}
			stepStateMask.invert();
			
			if ( targetStateAsInternal == null )
			{
				targetStateAsInternal = underlyingStateMachine.createInternalState(targetState);
				targetStateAsInternal.intersect(stepStateMask);
			}
			
			int largestDequeuePriority = -1;
			int bestGoalFound = -1;
			
			Set<ForwardDeadReckonInternalMachineState> visitedStates = new HashSet<ForwardDeadReckonInternalMachineState>();
			
			while(!AStarFringe.isEmpty())
			{
				AStarNode node = AStarFringe.remove();
				
				if ( node.getPriority() > largestDequeuePriority )
				{
					largestDequeuePriority = node.getPriority();
					
					System.out.println("Now dequeuing estimated cost " + largestDequeuePriority + " (fringe size " + AStarFringe.size() + ")");
				}
				
				if ( underlyingStateMachine.isTerminal(node.getState()))
				{
					int goalValue = underlyingStateMachine.getGoal(node.getState(), ourRole);
					
					if ( goalValue > bestGoalFound )
					{
						AStarSolutionPath = new LinkedList<Move>();
	
						//	Construct solution path
						while(node != null && node.getMove() != null)
						{
							AStarSolutionPath.add(0,node.getMove());
							node = node.getParent();
						}
						
						if ( goalValue == 100 )
						{
							//break;
						}
					}
				}
				
				//	Expand the node and add children to the fringe
				List<Move> childMoves = underlyingStateMachine.getLegalMoves(node.getState(), ourRole);
				
				if ( childMoves.size() == 0 )
				{
					System.out.println("No child moves found from state: " + node.getState());
				}
				for(Move move : childMoves)
				{
					List<Move> jointMove = new LinkedList<Move>();
					jointMove.add(move);
					
					ForwardDeadReckonInternalMachineState newState = underlyingStateMachine.getNextState(node.getState(), jointMove);
					ForwardDeadReckonInternalMachineState steplessState = new ForwardDeadReckonInternalMachineState(newState);
					steplessState.intersect(stepStateMask);
					
					if ( !visitedStates.contains(steplessState) )
					{
						AStarNode newChild = new AStarNode(newState, node, move);
						AStarFringe.add(newChild);
						
						visitedStates.add(steplessState);
						
						numAtDistance[newChild.pathLength]++;
					}
				}
			}
		}
		
		for(int i = 0; i < 50; i++)
		{
			System.out.println("Num states at distance " + i + ": " + numAtDistance[i]);
		}

		bestMove = AStarSolutionPath.remove(0);
		
		return bestMove;
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

	//	Find a move to play in an iterated game - currently only supports 2 player games
	private Move selectIteratedGameMove(List<Move> moves, long timeout) throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException
	{
		List<List<GdlTerm>> moveHistory = getMatch().getMoveHistory();
		List<Set<GdlSentence>> stateHistory = getMatch().getStateHistory();
		Move bestMove = null;
		Map<Move,Map<Move,Integer>> opponentMoveSelectionCounts = new HashMap<Move,Map<Move,Integer>>();
		Move lastPlayedOpponentChoice = null;
		Move lastPlayedOurChoice = null;
		int  lastPlayedOurChoiceSize = 0;
		
		if ( moves.size() == 1 )
		{
			return moves.get(0);
		}

		boolean responderInNonSimultaneousGame = (!isPseudoSimultaneousMove && moveHistory.size()%2 == 1);

		for(int i = 0; i < moveHistory.size(); i++)
		{
			List<GdlTerm> moveTerms = moveHistory.get(i);
			MachineState state = new MachineState(stateHistory.get(i));
			List<Move> historicalJointMove = new ArrayList<Move>();
			for (GdlTerm sentence : moveTerms)
			{
				historicalJointMove.add(underlyingStateMachine.getMoveFromTerm(sentence));
			}
			
			//	Did our opponent have a choice?
			if ( underlyingStateMachine.getLegalMoves(state, roleIndexToRole(1)).size() > 1 )
			{
				lastPlayedOpponentChoice = historicalJointMove.get(roleIndexToRawRoleIndex(1));
				
				//	Was this following a previous move of ours?  If so bump the response counts
				//	for that move
				if ( lastPlayedOurChoice != null )
				{
					Map<Move,Integer> moveWeights = opponentMoveSelectionCounts.get(lastPlayedOurChoice);
					if ( moveWeights == null )
					{
						moveWeights = new HashMap<Move,Integer>();
						opponentMoveSelectionCounts.put(lastPlayedOurChoice, moveWeights);
					}
					
					Integer val = moveWeights.get(lastPlayedOpponentChoice);
					
					moveWeights.put(lastPlayedOpponentChoice, (val == null ? lastPlayedOurChoiceSize : val + lastPlayedOurChoiceSize));
				}
			}
			
			//	Did we have a choice?
			if ( underlyingStateMachine.getLegalMoves(state, roleIndexToRole(0)).size() > 1 )
			{
				lastPlayedOurChoice = historicalJointMove.get(roleIndexToRawRoleIndex(0));
				lastPlayedOurChoiceSize = underlyingStateMachine.getLegalMoves(state, roleIndexToRole(0)).size();
				
				if ( lastPlayedOpponentChoice != null )
				{
					//	Bump for all moves we can play the count for the move the opponent played last
					for(Move legalMove : underlyingStateMachine.getLegalMoves(state, roleIndexToRole(0)))
					{
						Map<Move,Integer> moveWeights = opponentMoveSelectionCounts.get(legalMove);
						if ( moveWeights == null )
						{
							moveWeights = new HashMap<Move,Integer>();
							opponentMoveSelectionCounts.put(legalMove, moveWeights);
						}
						
						Integer val = moveWeights.get(lastPlayedOpponentChoice);
						
						moveWeights.put(lastPlayedOpponentChoice, (val == null ? 1 : val + 1));
					}
				}
			}
		}
		
		ForwardDeadReckonInternalMachineState currentState = underlyingStateMachine.createInternalState(getCurrentState());
		
		int 	bestScore = -1;
		int		opponentScoreAtBestScore = 0;
		for(Move move : moves)
		{
			ForwardDeadReckonInternalMachineState	state = new ForwardDeadReckonInternalMachineState(currentState);
			Map<Move,Integer> moveWeights = opponentMoveSelectionCounts.get(move);

			System.out.println("Considering move: " + move);
			if ( responderInNonSimultaneousGame )
			{
				System.out.println("We are responder so assuming opponent continues to play " + lastPlayedOpponentChoice);
			}
			else if ( moveWeights != null )
			{
				System.out.println("Response weights: " + moveWeights.values());
			}
			
			while(!underlyingStateMachine.isTerminal(state))
			{
				Move[] jointMove = new Move[2];
				int roleIndex = 0;
				
				for(Role role : underlyingStateMachine.getRoles())
				{
					List<Move> roleMoves = underlyingStateMachine.getLegalMoves(state, role);
					
					if ( roleMoves.size() == 1 )
					{
						jointMove[roleIndex] = roleMoves.get(0);
					}
					else if ( role.equals(ourRole))
					{
						if ( !roleMoves.contains(move))
						{
							System.out.println("Unexpectedly cannot play intended move in iterated game!");
						}
						jointMove[roleIndex] = move;
					}
					else if ( responderInNonSimultaneousGame )
					{
						jointMove[roleIndex] = lastPlayedOpponentChoice;
					}
					else
					{
						//	Do we have opponent response stats for this move?
						if ( moveWeights == null )
						{
							//	Assume flat distribution
							moveWeights = new HashMap<Move,Integer>();
							
							for(Move m : roleMoves)
							{
								moveWeights.put(m, 1);
							}
						}
						
						int total = 0;
						for(Integer weight : moveWeights.values())
						{
							total += weight;
						}
						
						int rand = r.nextInt(total);
						for(Move m : roleMoves)
						{
							Integer weight = moveWeights.get(m);
							if ( weight != null)
							{
								rand -= weight;
							}
							
							if ( rand < 0 )
							{
								jointMove[roleIndex] = m;
								break;
							}
						}
					}
					
					roleIndex++;
				}
				
				state = underlyingStateMachine.getNextState(state, jointMove);
			}
			
			int score = underlyingStateMachine.getGoal(ourRole);
			int opponentScore = underlyingStateMachine.getGoal(roleIndexToRole(1));
			
			if ( score >= opponentScore )
			{
				score += competitivenessBonus;
				if ( score > opponentScore )
				{
					score += competitivenessBonus;
				}
			}
			if ( score > bestScore || (score == bestScore && opponentScoreAtBestScore > opponentScore))
			{
				bestScore = score;
				opponentScoreAtBestScore = opponentScore;
				bestMove = move;
			}
		}
		
		return bestMove;
	}
	
	private class TreeSearcher implements Runnable
	{
		private volatile long							moveTime;
		private volatile long							startTime;
		private volatile int							searchSeqRequested = 0;
		private volatile int							searchSeqProcessing = 0;
		private volatile boolean						stopRequested = true;
		private volatile boolean						running = false;
		private int										numIterations = 0;
		private int										uhohCount = 0;
		public volatile boolean							requestYield = false;
		
		@Override
		public void run()
		{
			// TODO Auto-generated method stub
			try
			{
				while(SearchAvailable())
				{
					try
					{
						boolean complete = false;
						
						System.out.println("Move search started");
						//int validationCount = 0;
						
						//Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
						
						while(!complete && !stopRequested)
						{
							long time = System.currentTimeMillis();
							double percentThroughTurn = Math.min(100,(time - startTime)*100/(moveTime - startTime));
	
//							if ( Math.abs(lastPercentThroughTurn - percentThroughTurn) > 4 )
//							{
//								System.out.println("Percent through turn: " + percentThroughTurn + " - num iterations: " + numIterations + ", root has children=" + (root.children != null));
//								lastPercentThroughTurn = percentThroughTurn;
//							}
							if ( requestYield )
							{
								Thread.yield();
							}
							else
							{
								synchronized(treeLock)
								{
									complete = root.complete;
									
									if ( !complete )
									{
										explorationBias = maxExplorationBias - percentThroughTurn*(maxExplorationBias - minExplorationBias)/100;
										
										while ( numUsedNodes > transpositionTableSize - 200 )
										{
											root.disposeLeastLikelyNode();
										}
										//validateAll();
										//validationCount++;
										int numOutstandingRollouts = numQueuedRollouts - numCompletedRollouts;
										
										if ( numOutstandingRollouts < maxOutstandingRolloutRequests )
										{
											numIterations++;
											root.selectAction();
										}
										else
										{
											Thread.yield();
										}
										
										if (numRolloutThreads == 0)
										{
											while( !queuedRollouts.isEmpty() )
											{
												RolloutRequest request = queuedRollouts.remove();
												
												request.process(underlyingStateMachine);
											}
										}
		
	//									if ( numIterations == 0 )
	//									{
	//										int queuedSize = queuedRollouts.size();
	//										int completedSize = completedRollouts.size();
	//										System.out.println("Completing rollouts. numQueuedRollouts="+numQueuedRollouts+", numCompletedRollouts="+numCompletedRollouts);
	//										System.out.println("queuedRollouts.size()="+queuedSize);
	//										System.out.println("completedRollouts.size()="+completedSize);
	//										if ( numCompletedRollouts <= numQueuedRollouts+4 && queuedSize == 0 && completedSize == 0 )
	//										{
	//											System.out.println("Uh oh!");
	//											System.out.println("dequeuedRollouts="+dequeuedRollouts);
	//											System.out.println("enqueuedCompletedRollouts="+enqueuedCompletedRollouts);
	//											if ( ++uhohCount > 1 )
	//											{
	//												System.out.println("Persistent issue");
	//											}
	//										}
	//									}
										processCompletedRollouts();
									}
								}
							}
						}
						
						System.out.println("Move search complete");
					} catch (TransitionDefinitionException | MoveDefinitionException | GoalDefinitionException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		public int getNumIterations()
		{
			return numIterations;
		}
		
		private boolean SearchAvailable() throws InterruptedException
		{
			synchronized(this)
			{
				if ( searchSeqRequested == searchSeqProcessing || stopRequested )
				{
					running = false;
					this.notify();
					this.wait();
				}
				
				searchSeqProcessing = searchSeqRequested;
				running = true;
			}
			
			return true;
		}
		
		public void StartSearch(long moveTimeout, ForwardDeadReckonInternalMachineState startState)
		{
			System.out.println("Start move search...");
			synchronized(this)
			{
				moveTime	 = moveTimeout;
				startTime	 = System.currentTimeMillis();
				searchSeqRequested++;
				stopRequested = false;
				numIterations = 0;
				uhohCount = 0;
				
				if ( pieceStateMaps != null )
				{
					double total = 0;
					double ourPieceCount = 0;
					
					for(int i = 0; i < numRoles; i++)
					{
						rootPieceCounts[i] = pieceStateMaps[i].intersectionSize(root.state);
						total += rootPieceCounts[i];
						
						if ( i == 0 )
						{
							ourPieceCount = total;
						}
					}
					
					double ourMaterialDivergence = ourPieceCount - total/numRoles;
					
					//	Weight further material gain down the more we're already ahead/behind in material
					//	because in either circumstance it's likely to be position that is more important
					heuristicSampleWeight = (int)Math.max(2, 6 - Math.abs(ourMaterialDivergence)*3);
				}
				else
				{
					heuristicSampleWeight = 0;
				}
				
				this.notify();
			}
		}
		
		public void stop() throws InterruptedException
		{
			synchronized(this)
			{
				stopRequested = true;
				
				if ( running )
				{
					wait();
				}
			}
		}
	}
	
	private class StateMachineProxy extends StateMachine
	{
		private StateMachine machineToProxy;
		
		public StateMachineProxy(StateMachine proxyTo)
		{
			machineToProxy = proxyTo;
		}
		
		@Override
		public void initialize(List<Gdl> description)
		{
			machineToProxy.initialize(description);
		}

		@Override
		public int getGoal(MachineState state, Role role)
				throws GoalDefinitionException
		{
			synchronized(treeLock)
			{
				return machineToProxy.getGoal(state,  role);
			}
		}

		@Override
		public boolean isTerminal(MachineState state)
		{
			synchronized(treeLock)
			{
				return machineToProxy.isTerminal(state);
			}
		}

		@Override
		public List<Role> getRoles()
		{
			return machineToProxy.getRoles();
		}

		@Override
		public MachineState getInitialState()
		{
			return machineToProxy.getInitialState();
		}

		@Override
		public List<Move> getLegalMoves(MachineState state, Role role)
				throws MoveDefinitionException
		{
			synchronized(treeLock)
			{
				return machineToProxy.getLegalMoves(state, role);
			}
		}

		@Override
		public MachineState getNextState(MachineState state, List<Move> moves)
				throws TransitionDefinitionException
		{
			MachineState result;
			
			searchProcessor.requestYield = true;
			synchronized(treeLock)
			{
				result = machineToProxy.getNextState(state, moves);
			}
			searchProcessor.requestYield = false;
			
			return result;
		}
	}
	
	private TreeSearcher searchProcessor = null;
	
	@Override
	public Move stateMachineSelectMove(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException,
			GoalDefinitionException {
		// We get the current start time
		long start = System.currentTimeMillis();
		long finishBy = timeout - 2500;
	    Move bestMove;
		List<Move> moves;
	    
		if ( ProfilerContext.getContext() != null )
		{
			ProfilerContext.getContext().resetStats();
		}
		
		ForwardDeadReckonInternalMachineState currentState;
		
		searchProcessor.requestYield = true;
		
		System.out.println("Calculating current state, current time: " + System.currentTimeMillis());
		
		synchronized(treeLock)
		{
			currentState = underlyingStateMachine.createInternalState(getCurrentState());
			moves = underlyingStateMachine.getLegalMoves(currentState, ourRole);
			
			System.out.println("Received current state: " + getCurrentState());
			System.out.println("Using current state: " + currentState);
			
		    lowestRolloutScoreSeen = 1000;
		    highestRolloutScoreSeen = -100;
		    
			if ( underlyingStateMachine.isTerminal(currentState))
			{
				System.out.println("Asked to search in terminal state!");
			}
		}
		
		System.out.println("Setting search root, current time: " + System.currentTimeMillis());
		
		if ( isIteratedGame && numRoles == 2 )
		{
	    	bestMove = selectIteratedGameMove(moves, timeout);
			System.out.println("Playing best iterated game move: " + bestMove);
		}
		else if ( targetState != null )
	    {
	    	//bestMove = selectAStarMove(moves, timeout);
	    	bestMove = selectPuzzleMove(moves, timeout);
			System.out.println("Playing best puzzle move: " + bestMove);
	    }
	    else
	    {
			//emptyTree();
			//root = null;
			//validateAll();
			synchronized(treeLock)
			{
				//	Process anything left over from last turn's timeout
				processCompletedRollouts();
				
				if ( root == null )
				{
					root = allocateNode(underlyingStateMachine, currentState, null);
					root.decidingRoleIndex = numRoles-1;
				}
				else
				{
					TreeNode newRoot = root.findNode(currentState, underlyingStateMachine.getRoles().size()+1);
					if ( newRoot == null )
					{
						System.out.println("Unable to find root node in existing tree");
						emptyTree();
						root = allocateNode(underlyingStateMachine, currentState, null);
						root.decidingRoleIndex = numRoles-1;
					}
					else
					{
						if ( newRoot != root )
						{
							root.freeAllBut(newRoot);
							
							root = newRoot;
						}
					}
				}
				//validateAll();
				
				if ( root.complete && root.children == null )
				{
					System.out.println("Encountered complete root with trimmed children - must re-expand");
					root.complete = false;
					numCompletedBranches--;
				}
			}
			
			searchProcessor.StartSearch(finishBy, currentState);
			
			searchProcessor.requestYield = false;
			
			System.out.println("Waiting for processing, current time: " + System.currentTimeMillis());
			
			try {
				while(System.currentTimeMillis() < finishBy && !root.complete )
				{
					Thread.sleep(250);
				}
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			System.out.println("Timer expired, current time: " + System.currentTimeMillis());
			
			searchProcessor.requestYield = true;
			
			//validateAll();
			synchronized(treeLock)
			{
				System.out.println("Lock obtained, current time: " + System.currentTimeMillis());
				System.out.println("Num iterations: " + searchProcessor.getNumIterations());
				bestMove = root.getBestMove(true);
				
				if ( !moves.contains(bestMove))
				{
					System.out.println("Selected illegal move!!");
				}
				System.out.println("Playing move: " + bestMove);
				System.out.println("Num total tree node allocations: " + numTotalTreeNodes);
				System.out.println("Num unique tree node allocations: " + numUniqueTreeNodes);
				System.out.println("Num tree node frees: " + numFreedTreeNodes);
				System.out.println("Num tree nodes currently in use: " + numUsedNodes);
				System.out.println("Num true rollouts added: " + numNonTerminalRollouts);
				System.out.println("Num terminal nodes revisited: " + numTerminalRollouts);
				System.out.println("Num incomplete nodes: " + numIncompleteNodes);
				System.out.println("Num selections through incomplete nodes: " + numSelectionsThroughIncompleteNodes);
				System.out.println("Heuristic bias: " + heuristicSampleWeight);
				System.out.println("Num node re-expansions: " + numReExpansions);
				System.out.println("Num completely explored branches: " + numCompletedBranches);
				System.out.println("Current rollout sample size: " + rolloutSampleSize );
				System.out.println("Current observed rollout score range: [" + lowestRolloutScoreSeen + ", " + highestRolloutScoreSeen + "]");

				numSelectionsThroughIncompleteNodes = 0;
				numReExpansions = 0;
			    numNonTerminalRollouts = 0;
			    numTerminalRollouts = 0;
			}
			
			searchProcessor.requestYield = false;
			
			//validateAll();
	    }
		
		if ( ProfilerContext.getContext() != null )
		{
			GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
		}

		// We get the end time
		// It is mandatory that stop<timeout
		long stop = System.currentTimeMillis();
		
		Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
		
		System.out.println("Move took: " + (stop - start));

		if ( bestMove == null )
		{
			System.out.println("NO MOVE FOUND!");
			System.exit(0);
		}
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
