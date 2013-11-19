package org.ggp.base.player.gamer.statemachine.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.ggp.base.player.gamer.event.GamerSelectedMoveEvent;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.profile.ProfilerContext;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.propnet.TestForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.gdl.grammar.GdlSentence;

public class Qixote extends SampleGamer {
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
    
    private Map<ForwardDeadReckonInternalMachineState, TreeNode> positions = new HashMap<ForwardDeadReckonInternalMachineState,TreeNode>();

    private int rolloutSampleSize = 5;
    private final int minRolloutSampleSize = 1;
    private final int maxRolloutSampleSize = 500;
    private final int transpositionTableSize = 500000;
    private final int transpositinoTableMaxDesiredSizeAtTurnEnd = transpositionTableSize - 100;
    private final int transpositionTableMaxSizeAtProbeEnd = transpositionTableSize - 100;
    private final int maxOutstandingRolloutRequests = 8;
    private final int numRolloutThreads = 4;
    private TreeNode[] transpositionTable = new TreeNode[transpositionTableSize];
    private int nextSeq = 0;
    private List<TreeNode> freeList = new LinkedList<TreeNode>();
    private int largestUsedIndex = -1;
    private int sweepInstance = 0;
    private List<TreeNode> completedNodeQueue = new LinkedList<TreeNode>();
    private LinkedBlockingQueue<RolloutRequest>	queuedRollouts = new LinkedBlockingQueue<RolloutRequest>();
    private LinkedBlockingQueue<RolloutRequest>	completedRollouts = new LinkedBlockingQueue<RolloutRequest>();
    private int numQueuedRollouts = 0;
    private int numCompletedRollouts = 0;
    private RolloutProcessor[] rolloutProcessors = null;
    
    @SuppressWarnings("unused")
	private class RolloutProcessor implements Runnable
    {
    	private boolean stop = false;
    	private TestForwardDeadReckonPropnetStateMachine stateMachine;
    	private Thread runningThread = null;
    	private AtomicInteger utilization = new AtomicInteger(1000);
    	
    	public RolloutProcessor(TestForwardDeadReckonPropnetStateMachine stateMachine)
    	{
    		this.stateMachine = stateMachine;
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
    	
    	public int getUtilization()
    	{
    		return utilization.get()/10;
    	}
    	
		@Override
		public void run()
		{
			try
			{
				while(!stop)
				{
					RolloutRequest request = queuedRollouts.poll();
					if ( request == null )
					{
						int util = utilization.get();
						utilization.set((util*9)/10);
						//System.out.println("decreased utilization to " + (util*99)/100);
						request = queuedRollouts.take();
					}
					else
					{
						utilization.getAndAdd(100);
						//System.out.println("increased utilization to " + utilization.get());
						//System.out.println("Queue size is " + queuedRollouts.size());
					}
					
					//System.out.println("Queue size is " + queuedRollouts.size());
					
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
    
    private class RolloutRequest
    {
    	public TreeNodeRef								node;
    	public ForwardDeadReckonInternalMachineState	state;
    	public double									score;
    	public int										sampleSize;
	    
	    public  void process(TestForwardDeadReckonPropnetStateMachine stateMachine) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	    {
			ProfileSection methodSection = new ProfileSection("TreeNode.rollOut");
			try
			{
				score = 0;
				for(int i = 0; i < sampleSize; i++)
				{
					//System.out.println("Perform rollout from state: " + state);
		        	stateMachine.getDepthChargeResult(state, ourRole, 1000, null, null);
		        	
		        	score += netScore(stateMachine, null);
				}
				
				score /= sampleSize;
				
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
	
	private int netScore(TestForwardDeadReckonPropnetStateMachine stateMachine, ForwardDeadReckonInternalMachineState state) throws GoalDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("TreeNode.netScore");
		try
		{
        	int result = 0;
        	int enemyRoleCount = 0;
        	int enemyScore = 0;
        	for(Role role : stateMachine.getRoles())
        	{
        		if ( !role.equals(ourRole) )
        		{
        			enemyRoleCount++;
        			enemyScore += stateMachine.getGoalNative(state, role);
        		}
        		else
        		{
        			result = stateMachine.getGoalNative(state, role);
        		}
        	}
        	
        	return (result - enemyScore + 100*enemyRoleCount)/(enemyRoleCount+1);
		}
		finally
		{
			methodSection.exitScope();
		}
	}
    
    private void processCompletedRollouts(boolean block) throws InterruptedException
    {
    	RolloutRequest request;
    	
    	do
    	{
    		request = (block ? completedRollouts.take() : completedRollouts.poll());
    		
    		if ( request != null )
    		{
	    		TreeNode 	   node = request.node.node;
	    		
	    		if ( request.node.seq == node.seq && !node.complete )
	    		{
	    			numNonTerminalRollouts += request.sampleSize;
	    			
			        node.updateStats(request.score, request.sampleSize, false);
		    		processNodeCompletions();
	    		}
	    		
	    		numCompletedRollouts++;
    		}
    		
    		block = false;
    	} while(request != null);
    }
	
	private void processNodeCompletions()
	{
		while(!completedNodeQueue.isEmpty())
		{
			TreeNode node = completedNodeQueue.remove(0);
			
			if ( !node.freed )
			{
				node.processCompletion();
			}
		}
	}
  
	private TreeNode allocateNode(TestForwardDeadReckonPropnetStateMachine underlyingStateMachine, ForwardDeadReckonInternalMachineState state, Move ourMove, TreeNode parent) throws GoalDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("allocatNode");
		try
		{
			TreeNode result = (ourMove == null ? positions.get(state) : null);
			
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
					
					result.reset();
					result.ourMove = ourMove;
					result.state = state;
				}
				else
				{
					throw new RuntimeException("Unexpectedly full transition table");
				}
				
				result.setStateAndMove(underlyingStateMachine, state, ourMove);
				result.seq = nextSeq++;
				
				//if ( positions.values().contains(result))
				//{
				//	System.out.println("Node already referenced by a state!");
				//}
				if ( ourMove == null )
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
				if ( result.ourMove != null )
				{
					System.out.println("Non-null move in position cache");
				}
			}
			
			if ( result.ourMove != ourMove)
			{
				System.out.println("Move mismatch");
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
   
	private class TreeNode
	{
	    static final double epsilon = 1e-6;

	    private int seq = -1;
		private Move ourMove;
		private double numVisits = 0;
		private double averageScore;
		private ForwardDeadReckonInternalMachineState state;
		private boolean isTerminal = false;
		private TreeNodeRef[] children = null;
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
		}
		
		private void setStateAndMove(TestForwardDeadReckonPropnetStateMachine underlyingStateMachine, ForwardDeadReckonInternalMachineState state, Move ourMove) throws GoalDefinitionException
		{
			this.state = state;
			this.ourMove = ourMove;
			
			//System.out.println("Set node state: " + state + " with hash " + state.hashCode());
			if ( ourMove == null )
			{
				isTerminal = underlyingStateMachine.isTerminal(state);
				//boolean isTerminal2 = rolloutStateMachine.isTerminal(state);
				//if ( isTerminal != isTerminal2 )
				//{
				//	System.out.println("Instance disagrement on terminality");
				//}
				if ( isTerminal )
				{
					averageScore = 100 - netScore(underlyingStateMachine, state);
					
					if ( averageScore < 0.5 )
					{
						numWinningLinesSeen++;
					}
					else if ( averageScore > 99.5 )
					{
						numLosingLinesSeen++;
					}
					//System.out.println("Reached terminal state with score " + averageScore + " : "+ state);
				}
			}
			else
			{
				isTerminal = false;
			}
		}
		
		private void markComplete()
		{
			if ( !complete )
			{
				complete = true;
				numCompletedBranches++;
				
				//System.out.println("Mark complete with score " + averageScore + (ourMove == null ? " (for opponent)" : " (for us)") + " in state: " + state);
				if ( this == root )
				{
					System.out.println("Mark root complete");
				}
				else
				{
					completedNodeQueue.add(this);
				}
				
				if ( trimmedChildren > 0 )
				{
					//	Don't consider a complete node in the incomplete counts ever
					numIncompleteNodes--;
				}
			}
		}
		
		private void processCompletion()
		{
			//	Children can all be freed, at least from this parentage
			//	We suppress this for winning (or drawing) lines for us when the transposition
			//	table is not full however, to retain known useful lines
			boolean retainUsefulLine = false;//(ourMove != null && averageScore > 49.5) || (ourMove == null && averageScore < 50.5);
			if ( children != null )
			{
				int numDescendantsFreed = 0;
				
				for(TreeNodeRef cr : children)
				{
					if ( cr.node.seq == cr.seq )
					{
						if ( !retainUsefulLine || !cr.node.complete || Math.abs(averageScore + cr.node.averageScore - 100) > 0.5 )
						{
							numDescendantsFreed += cr.node.descendantCount + 1;
							cr.node.freeFromAncestor(this);
							
		            		trimmedChildren++;
		            		
		            		cr.seq = -1;
						}
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
			
			for(TreeNode parent : parents)
			{
				if ( averageScore > 99.5 )
				{
					// Win for whoever just moved after they got to choose so parent node is also decided
					parent.averageScore = 0;
					parent.markComplete();
				}
				else
				{
					//	If all children are complete then the parent is - give it a chance to
					//	decide
					parent.checkChildCompletion();
				}
			}
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
					for(TreeNodeRef cr : children)
					{
						if ( cr.node.seq == cr.seq )
						{
							cr.node.freeFromAncestor(this);
						}
					}
				}
				
				freeNode();
			}
		}
		
		private void checkChildCompletion()
		{
			boolean allChildrenComplete = true;
			double bestValue = 0;
			
			for(TreeNodeRef cr : children)
			{
				if ( cr.node.seq == cr.seq )
				{
					if ( !cr.node.complete )
					{
						allChildrenComplete = false;
					}
					else if ( cr.node.averageScore > bestValue )
					{
						bestValue = cr.node.averageScore;
					}
				}
				else
				{
					allChildrenComplete = false;
				}
			}
			
			if ( allChildrenComplete || bestValue > 99.5 )
			{
				//	Opponent's choice which child to take, so take their
				//	best value and crystalize as our value
				averageScore = 100 - bestValue;
				markComplete();
			}
		}
		
		public void reset()
		{
			numVisits = 0;
			averageScore = 0;
			state = null;
			isTerminal = false;
			children = null;
			parents.clear();
			trimmedChildren = 0;
			freed = false;
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
	    	if ( freed )
	    	{
	    		System.out.println("Manipulating deleted node");
	    	}
			for(TreeNode parent : parents)
			{
				parent.adjustDescendantCounts(adjustment);
			}
			
			descendantCount += adjustment;
	    }
		
		private int validate()
		{
			int descendants = 0;
			
			if ( children != null )
			{
				int missingChildren = 0;
				for(TreeNodeRef cr : children)
				{
					if ( cr != null )
					{
						if ( cr.node.seq == cr.seq )
						{
							if ( !cr.node.parents.contains(this))
							{
								System.out.println("Missng parent link");
							}
							if ( cr.node.complete && cr.node.averageScore > 99.5 && !complete && !completedNodeQueue.contains(cr.node) )
							{
								System.out.println("Completeness constraint violation");
							}
							if ( (cr.node.ourMove == null) != (ourMove != null) )
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
			sweepSeq = sweepInstance;
			if( children != null )
			{
				for(TreeNodeRef cr : children)
				{
					if ( cr.node.seq == cr.seq )
					{
						//if ( !cr.node.parents.contains(this))
						//{
						//	System.out.println("Child relation inverse missing");
						//}
						//cr.node.sweepParent = this;
						cr.node.markTreeForSweep();
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
				if ( ourMove == null )
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
				}
				if ( complete )
				{
					numCompletedBranches--;
				}
				
				if ( children != null )
				{
					for(TreeNodeRef cr : children)
					{
						if ( cr != null )
						{
							if ( cr.node.seq == cr.seq )
							{
								if ( cr.node.parents.size() != 0)
								{
									int numRemainingParents = cr.node.parents.size();
									//if ( cr.node.sweepParent == this && sweepSeq == sweepInstance)
									//{
									//	System.out.println("Removing sweep parent");
									//}
									cr.node.parents.remove(this);
									if ( numRemainingParents == 0)
									{
										System.out.println("Orphaned child node");
									}
									else
									{
										//	Best estimate of likely paths to the child node given removal of parent
										cr.node.numVisits = (cr.node.numVisits*numRemainingParents)/(numRemainingParents+1);
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
				for(TreeNodeRef cr : children)
				{
					if ( cr.node.seq == cr.seq )
					{
						cr.node.freeAllBut(null);
					}
				}
			}
			
			freeNode();
		}
		
		public TreeNode findNode(ForwardDeadReckonInternalMachineState targetState)
		{
			if ( state.equals(targetState) && ourMove == null )
			{
				return this;
			}
			
			if ( children != null )
			{
				for(TreeNodeRef cr : children)
				{
					if( cr.node.seq == cr.seq )
					{
						TreeNode childResult = cr.node.findNode(targetState);
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
				TreeNode leastLikely = selectLeastLikelyNode(0);
				
				leastLikely.adjustDescendantCounts(-1);
				leastLikely.freeNode();
			}
			finally
			{
				methodSection.exitScope();
			}
		}
		
		public TreeNode selectLeastLikelyNode(int depth)
		{
	        int selectedIndex = -1;
	        double bestValue = -Double.MAX_VALUE;
	        
	        //System.out.println("Select LEAST in " + state);
	        if ( freed )
	        {
	        	System.out.println("Encountered freed node in tree walk");
	        }
	        if ( children != null )
	        {
	        	if ( children.length == 1 )
	        	{
	        		TreeNodeRef cr = children[0];
	        		
	        		if ( cr.node.seq == cr.seq )
	        		{
	        			selectedIndex = 0;
	        		}
	        	}
	        	else
	        	{
		        	if ( leastLikelyWinner != -1 )
		        	{
		        		TreeNodeRef cr = children[leastLikelyWinner];
		        		TreeNode c = cr.node;
		        		if ( cr.seq == c.seq )
		        		{
				            double uctValue;
				            if ( c.numVisits == 0 )
				            {
				            	uctValue = -1000;
				            }
				            else
				            {
				            	uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,c.numVisits)+1) / c.numVisits);
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
				        	TreeNodeRef cr = children[i];
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
						            if ( c.numVisits == 0 )
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
						            	uctValue = -c.averageScore/100 - Math.sqrt(Math.log(Math.max(numVisits,c.numVisits)+1) / c.numVisits);
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

	        if ( selectedIndex != -1 )
	        {
	        	leastLikelyWinner = selectedIndex;
		        //System.out.println("  selected: " + selected.state);
	        	return children[selectedIndex].node.selectLeastLikelyNode(depth+1);
	        }
	        
	        if ( descendantCount > 0 )
	        {
	        	System.out.println("Selecting non-leaf for removal!");
	        }
	        
	        if ( depth < 2 )
	        	System.out.println("Selected unlikely node at depth " + depth);
	        return this;
		}
		
	    public void selectAction() throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
			ProfileSection methodSection = new ProfileSection("TreeNode.selectAction");
			try
			{
				completedNodeQueue.clear();
				
		        //List<TreeNode> visited = new LinkedList<TreeNode>();
		        TreeNode cur = this;
		        //visited.add(this);
		        while (!cur.isUnexpanded()) {
		            cur = cur.select();
		            //visited.add(cur);
		        }
		        
		        TreeNode newNode;
		        if ( !cur.complete )
		        {
			        cur.expand();
			        
			        if ( !cur.complete )
			        {
				        newNode = cur.select();
				        //visited.add(newNode);
				        if ( newNode.ourMove != null )
				        {
				        	newNode.expand();
				        	if ( !newNode.complete )
				        	{
					        	newNode = newNode.select();
						        //visited.add(newNode);
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
		        	//	from it so it's value gets a weight increase via back propagation
		        	newNode = cur;
		        }
		        
		        double score = newNode.rollOut();
		        if ( score != -Double.MAX_VALUE )
		        {
		        	newNode.updateStats(score, rolloutSampleSize, true);
		    		processNodeCompletions();
		        }
		        else
		        {
		        	newNode.updateVisitCounts(rolloutSampleSize);
		        }
			}
			finally
			{
				methodSection.exitScope();
			}
	    }

	    public void expand() throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
			ProfileSection methodSection = new ProfileSection("TreeNode.expand");
			try
			{
		    	if ( children == null || trimmedChildren > 0 )
		    	{
		    		//validateAll();
		    		
			    	if ( ourMove == null )
			    	{
			    		//System.out.println("Expand our moves from state: " + state);
			    		List<Move> moves = underlyingStateMachine.getLegalMoves(state, ourRole);
			    		//List<Move> moves2 = rolloutStateMachine.getLegalMoves(state, ourRole);
			    		//if ( !moves.equals(moves2))
			    		//{
			    		//	System.out.println("Instance disagreement");
			    		//}
			    		TreeNodeRef[] newChildren = new TreeNodeRef[moves.size()];
			    		
			    		if ( children != null )
			    		{
			    			int index = 0;
			    			for(TreeNodeRef cr : children)
			    			{
			    				TreeNode child = cr.node;
			    				if ( cr.seq == child.seq )
			    				{
			    					moves.remove(child.ourMove);
			    					newChildren[index] = cr;
			    				}
			    				
			    				index++;
			    			}
			    		}
		    			for(int index = 0; index < newChildren.length; index++)
		    			{
		    				if ( newChildren[index] == null )
		    				{
		    					newChildren[index] = allocateNode(underlyingStateMachine, state, moves.remove(0), this).getRef();
		    				}
		    			}
		    			
		    			children = newChildren;
			    		//validateAll();
		    		}
			    	else
			    	{
			    		//System.out.println("Expand opponent moves for move " + ourMove + " from state: " + state);
			    		List<List<Move>> legalMoves = new ArrayList<List<Move>>();
			    		
			    		for(Role role : underlyingStateMachine.getRoles())
			    		{
			    			if ( !role.equals(ourRole) )
			    			{
					    		List<Move> moves = underlyingStateMachine.getLegalMoves(state, role);
					    		//List<Move> moves2 = rolloutStateMachine.getLegalMoves(state, role);
					    		//if ( !moves.equals(moves2))
					    		//{
					    		//	System.out.println("Instance disagreement");
					    		//}
			    				legalMoves.add(moves);
			    			}
			    			else
			    			{
			    				List<Move> myMoveList = new ArrayList<Move>();
			    				
			    				myMoveList.add(ourMove);
			    				legalMoves.add(myMoveList);
			    			}
			    		}
			    		
			    		//	Now try all possible opponent moves and assume they will choose the joint worst
			    		List<List<Move>> jointMoves = new LinkedList<List<Move>>();
			    		
			    		flattenMoveLists(legalMoves, jointMoves);
			    		
			    		TreeNodeRef[] newChildren = new TreeNodeRef[jointMoves.size()];
			    		List<ForwardDeadReckonInternalMachineState> newStates = new LinkedList<ForwardDeadReckonInternalMachineState>();
			    		
			    		for(List<Move> jointMove : jointMoves)
			    		{
			    			//System.out.println("Determine next state after joint move " + jointMove);
			    			newStates.add(underlyingStateMachine.getNextState(state, jointMove));
			    		}
			    		if ( children != null )
			    		{
				    		int index = 0;
			    			for(TreeNodeRef cr : children)
			    			{
			    				TreeNode child = cr.node;
			    				if ( cr.seq == child.seq )
			    				{
			    					newStates.remove(child.state);
			    					newChildren[index] = cr;
			    				}
			    				
			    				index++;
			    			}
			    		}
		    			for(int index = 0; index < newChildren.length; index++)
		    			{
			    			if ( newChildren[index] == null )
			    			{
				    			newChildren[index] = allocateNode(underlyingStateMachine, newStates.remove(0), null, this).getRef();
			    			}
			    		}
			    		
			    		children = newChildren;
			    		//validateAll();
			    	}
			    	
	        		if ( trimmedChildren > 0 )
	        		{
	        			trimmedChildren = 0;	//	This is a fresh expansion entirely can go back to full UCT
	        			numIncompleteNodes--;
	        		}
			    	
			    	boolean completeChildFound = false;
			    	
					for(TreeNodeRef cr : children)
					{
						if ( cr.node.seq == cr.seq )
						{
							if ( cr.node.isTerminal )
							{
								cr.node.markComplete();
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

	    private TreeNode select() throws MoveDefinitionException, TransitionDefinitionException, GoalDefinitionException {
	        TreeNode selected = null;
	        int selectedIndex = -1;
	        
	        //System.out.println("Select in " + state);
	        if ( trimmedChildren == 0 )
	        {
		        double bestValue = Double.MIN_VALUE;
		        
		        if ( children != null )
		        {
		        	if ( children.length == 1 )
		        	{
		        		TreeNodeRef cr = children[0];
		        		
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
			        		TreeNodeRef cr = children[mostLikelyWinner];
			        		TreeNode c = cr.node;
			        		if ( cr.seq == c.seq )
			        		{
					            double uctValue;
					            
					            if ( c.numVisits == 0 )
					            {
						            // small random number to break ties randomly in unexpanded nodes
					            	uctValue = 1000 +  r.nextDouble() * epsilon;
					            }
					            else
					            {
					            	uctValue = c.averageScore/100 + Math.sqrt(Math.log(Math.max(numVisits,c.numVisits)+1) / c.numVisits);
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
					        	TreeNodeRef cr = children[i];
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
						            else if ( !cr.node.complete )
						            {
							            double uctValue;
							            if ( c.numVisits == 0 )
							            {
								            // small random number to break ties randomly in unexpanded nodes
							            	uctValue = 1000 +  r.nextDouble() * epsilon;
							            }
							            else
							            {
							            	uctValue = c.averageScore/100 + Math.sqrt(Math.log(Math.max(numVisits,c.numVisits)+1) / c.numVisits);
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
	        		System.out.println("no selction found on untrimmed ndoe!");
	        	}
	        	//System.out.println("  select random");
	        	//	pick at random.  If we pick one that has been trimmed re-expand it
	        	//	FUTURE - can establish a bound on the trimmed UCT value to avoid
	        	//	randomization for a while at least
	        	int childIndex = r.nextInt(children.length);
	        	TreeNodeRef cr = children[childIndex];
	        	selected = cr.node;
	        	if ( cr.seq != selected.seq )
	        	{
	        		expand();
	        		selected = children[childIndex].node;
		        	
		        	if ( selected.freed )
		        	{
		        		System.out.println("Selected freed node!");
		        	}
		        	if ( selected.complete )
		        	{
		        		System.out.println("Selected complete node from incompleete parent");
		        	}
	        	}
	        }
	        else
	        {
	        	mostLikelyWinner = selectedIndex;
	        	selected = children[selectedIndex].node;
	        	
	        	if ( selected.freed )
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
	    
	    public Move getBestMove()
	    {
	    	double bestScore = -1;
	    	Move result = null;
	    	
	    	for(TreeNodeRef cr : children)
	    	{
	    		TreeNode child = cr.node;
	    		System.out.println("Move " + child.ourMove + " scores " + child.averageScore + " (selection count " + child.numVisits + (child.complete ? ", complete" : "") + ")");
	    		if ( child.averageScore > bestScore || (child.averageScore == bestScore && child.complete))
	    		{
	    			bestScore = child.averageScore;
	    			result = child.ourMove;
	    		}
	    	}
	    	
	    	if ( result == null )
	    	{
	    		System.out.println("No move found!");
	    	}
	    	return result;
	    }

	    public double rollOut() throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException
	    {
	        if ( complete )
	        {
	        	//System.out.println("Terminal state " + state + " score " + averageScore);
	        	numTerminalRollouts++;
	        	if ( ourMove == null )
	        	{
	        		return 100 - averageScore;
	        	}
	        	else
	        	{
	        		return averageScore;
	        	}
	        }
	        else
	        {
	        	if ( ourMove != null )
	        	{
	        		System.out.println("Unexpected rollout state");
	        	}
	        	
	        	RolloutRequest request = new RolloutRequest();
	        	request.state = state;
	        	request.node = getRef();
	        	request.sampleSize = rolloutSampleSize;
	        	
	        	numQueuedRollouts++;
	        	//System.out.println("Queue rollout request");
	        	queuedRollouts.add(request);
	        	
	        	return -Double.MAX_VALUE;
	        }
	    }

	    public void updateVisitCounts(int sampleSize)
	    {
	    	numVisits += sampleSize;
	    	
	    	for(TreeNode parent : parents)
	    	{
	    		parent.updateVisitCounts(sampleSize);
	    	}
	    }
	    
	    public void updateStats(double value, int sampleSize, boolean updateVisitCounts)
	    {
	    	int adjustedSampleSize = sampleSize;
	    	
	    	if ( !complete )
	    	{
	    		double weightedScore;
	    		
	    		if (value < 25)
	    		{
	    			//adjustedSampleSize *= 50;
	    		}
	    		
		    	if ( ourMove == null )
		    	{
		    		weightedScore = (100-value)*adjustedSampleSize;
		    		averageScore = (averageScore*numVisits + weightedScore)/(numVisits+adjustedSampleSize);
		    	}
		    	else
		    	{
		    		weightedScore = value*sampleSize;
		    		averageScore = (averageScore*numVisits + weightedScore)/(numVisits+adjustedSampleSize);
		    	}
	    	}
	    	
	    	if ( updateVisitCounts)
	    	{
	    		numVisits += sampleSize;
	    	}
	    	else if ( adjustedSampleSize != sampleSize )
	    	{
	    		numVisits += adjustedSampleSize - sampleSize;
	    	}
	    	
	    	leastLikelyWinner = -1;
	    	mostLikelyWinner = -1;
	    	
	    	for(TreeNode parent : parents)
	    	{
	    		parent.updateStats(value, sampleSize, updateVisitCounts);
	    	}
	    }
	}
	
	private void validateAll()
	{
		if ( root != null )
			root.validate();
		
		for(Entry<ForwardDeadReckonInternalMachineState, TreeNode> e : positions.entrySet())
		{
			if ( e.getValue().ourMove != null )
			{
				System.out.println("Position references bad type");
			}
			if ( !e.getValue().state.equals(e.getKey()))
			{
				System.out.println("Position state mismatch");
			}
		}
		
		for(TreeNode node : transpositionTable)
		{
			if ( node != null && !node.freed )
			{
				if ( node.ourMove == null )
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
	}

	private void emptyTree()
	{
		numUniqueTreeNodes = 0;
		numTotalTreeNodes = 0;
		numFreedTreeNodes = 0;
		numCompletedBranches = 0;
		numUsedNodes = 0;
		root = null;
		largestUsedIndex = -1;
		positions.clear();
		freeList.clear();
		queuedRollouts.clear();
		completedRollouts.clear();
		numQueuedRollouts = 0;
		numCompletedRollouts = 0;
	}
	
	private TestForwardDeadReckonPropnetStateMachine underlyingStateMachine;
	private TreeNode root = null;
		
	@Override
	public String getName() {
		return "Quixote 0.24";
	}
	
	@Override
	public StateMachine getInitialStateMachine() {
	    if ( rolloutProcessors != null )
	    {
	    	for(int i = 0; i < numRolloutThreads; i++)
	    	{
	    		rolloutProcessors[i].stop();
	    	}
	    	
	    	rolloutProcessors = null;
	    }
		GamerLogger.setFileToDisplay("StateMachine");
		//ProfilerContext.setProfiler(new ProfilerSampleSetSimple());
		underlyingStateMachine = new TestForwardDeadReckonPropnetStateMachine(1+numRolloutThreads);
		
		emptyTree();

		return underlyingStateMachine;
	}
	
	@Override
	public Move stateMachineSelectMove(long timeout)
			throws TransitionDefinitionException, MoveDefinitionException,
			GoalDefinitionException {
		// We get the current start time
		long start = System.currentTimeMillis();
		long finishBy = timeout - 1000;
	    numNonTerminalRollouts = 0;
	    numTerminalRollouts = 0;
		
	    if ( rolloutProcessors == null )
	    {
	    	rolloutProcessors = new RolloutProcessor[numRolloutThreads];
	    	
	    	for(int i = 0; i < numRolloutThreads; i++)
	    	{
	    		rolloutProcessors[i] = new RolloutProcessor(underlyingStateMachine.createInstance());
	    		rolloutProcessors[i].start();
	    	}
	    }
	    
		ourRole = getRole();
		
		ForwardDeadReckonInternalMachineState currentState = underlyingStateMachine.createInternalState(getCurrentState());
		
		emptyTree();
		root = null;
		//validateAll();
		if ( root == null )
		{
			root = allocateNode(underlyingStateMachine, currentState, null, null);
		}
		else
		{
			System.out.println("Searching for new root in state: " + currentState);
			TreeNode newRoot = root.findNode(currentState);
			if ( newRoot == null )
			{
				System.out.println("Unexpectedly unable to find root node in existing tree");
				emptyTree();
				root = allocateNode(underlyingStateMachine, currentState, null, null);
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
		
		if ( root.complete )
		{
			System.out.println("Encountered complete root - must re-expand");
			root.complete = false;
			numCompletedBranches--;
		}
		
		int loopCount = 0;
		long utilizationSampleCount = 0;
		long averageRolloutProcessorUtilization = 0;
		
		while(System.currentTimeMillis() < finishBy && !root.complete )
		{
			//validateAll();
			loopCount++;
			int numOutstandingRollouts = numQueuedRollouts - numCompletedRollouts;
			
			if ( numOutstandingRollouts < maxOutstandingRolloutRequests )
			{
				root.selectAction();
			}
			//else
			//{
				// TEMP - do this on the main thread for initial debugging
			//	while( !queuedRollouts.isEmpty() )
			//	{
			//		RolloutRequest request = queuedRollouts.remove();
					
			//		request.process(rolloutStateMachine);
			//	}
			//}
			//validateAll();
			try {
				processCompletedRollouts(numOutstandingRollouts >= maxOutstandingRolloutRequests);
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
				break;
			}

			if ( loopCount % (maxOutstandingRolloutRequests*16) == 0 )
			{
				int rolloutProcessorUtilization = 0;
				for( int i = 0; i < numRolloutThreads; i++)
				{
					rolloutProcessorUtilization += rolloutProcessors[i].getUtilization();
				}
				rolloutProcessorUtilization /= numRolloutThreads;
				if ( rolloutProcessorUtilization < 100 )
				{
					if ( rolloutSampleSize < maxRolloutSampleSize )
					{
						rolloutSampleSize++;
						//System.out.println("Rollout process utilization " + rolloutProcessorUtilization + ": increasing sample size to " + rolloutSampleSize);
					}
				}
				else if ( rolloutProcessorUtilization > 400 )
				{
					if ( rolloutSampleSize > minRolloutSampleSize )
					{
						rolloutSampleSize--;
						//System.out.println("Rollout process utilization " + rolloutProcessorUtilization + ": decreasing sample size to " + rolloutSampleSize);
					}
				}
				
				averageRolloutProcessorUtilization += rolloutProcessorUtilization;
				utilizationSampleCount++;
			}
			
			while ( numUsedNodes > transpositinoTableMaxDesiredSizeAtTurnEnd )
			{
				root.disposeLeastLikelyNode();
			}
		}
		
		if ( utilizationSampleCount != 0 )
		{
			averageRolloutProcessorUtilization /= utilizationSampleCount;
		}
		
		//validateAll();
		List<Move> moves = underlyingStateMachine.getLegalMoves(currentState, ourRole);
		Move bestMove = root.getBestMove();
		
		while ( numUsedNodes > transpositionTableMaxSizeAtProbeEnd )
		{
			root.disposeLeastLikelyNode();
		}
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
		System.out.println("Average rollout utilization: " + averageRolloutProcessorUtilization );
		System.out.println("Current rollout sample size: " + rolloutSampleSize );
		
		if ( ProfilerContext.getContext() != null )
		{
			GamerLogger.log("GamePlayer", "Profile stats: \n" + ProfilerContext.getContext().toString());
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

}
