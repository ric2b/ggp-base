package org.ggp.base.util.statemachine.implementation.propnet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlRelation;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.factory.OptimizingPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.PolymorphicTransition;
import org.ggp.base.util.propnet.polymorphic.bidirectionalPropagation.BidirectionalPropagationComponent;
import org.ggp.base.util.propnet.polymorphic.factory.OptimizingPolymorphicPropNetFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponent;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonComponentFactory;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropositionCrossReferenceInfo;
import org.ggp.base.util.propnet.polymorphic.learning.LearningComponent;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;
import org.ggp.base.util.statemachine.implementation.prover.query.ProverQueryBuilder;
import org.ggp.base.util.stats.Stats;

public class TestForwardDeadReckonPropnetStateMachine extends StateMachine {
	private class MoveInputSet
	{
		public PolymorphicProposition[] moveInputPropositions;
		int								numPropositions;
	}
	
	private class InternalMachineState implements Iterable<ForwardDeadReckonPropositionCrossReferenceInfo>
	{
		private class InternalMachineStateIterator implements Iterator<ForwardDeadReckonPropositionCrossReferenceInfo>
		{
			private InternalMachineState parent;
			int	index;
			
			public InternalMachineStateIterator(InternalMachineState parent)
			{
				this.parent = parent;
				index = parent.contents.nextSetBit(0);
			}
			
			@Override
			public boolean hasNext() {
				return (index != -1);
			}

			@Override
			public ForwardDeadReckonPropositionCrossReferenceInfo next() {
				ForwardDeadReckonPropositionCrossReferenceInfo result = parent.infoSet[index];
				index = parent.contents.nextSetBit(index+1);
				return result;
			}

			@Override
			public void remove() {
				// TODO Auto-generated method stub
				
			}
		}
		
		private ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet;
		
		BitSet contents = new BitSet();
		//Set<ForwardDeadReckonPropositionCrossReferenceInfo> contents = new HashSet<ForwardDeadReckonPropositionCrossReferenceInfo>();
		boolean isXState = false;
		
		public InternalMachineState(ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet, GdlSentence XSentence, MachineState state)
		{
			this.infoSet = infoSet;
			
			ProfileSection methodSection = new ProfileSection("InternalMachineState.constructFromMachineState");
			try
			{
				for(GdlSentence s : state.getContents())
				{
					ForwardDeadReckonProposition p = (ForwardDeadReckonProposition)propNet.getBasePropositions().get(s);
					ForwardDeadReckonPropositionCrossReferenceInfo info = p.getCrossReferenceInfo();
					contents.set(info.index);
					
					isXState |= (info.sentence == XSentence);
				}
			}
			finally
			{
				methodSection.exitScope();
			}
		}
		
		public InternalMachineState(ForwardDeadReckonPropositionCrossReferenceInfo[] infoSet) {
			this.infoSet = infoSet;
		}

		public void add(ForwardDeadReckonPropositionCrossReferenceInfo info)
		{
			contents.set(info.index);
		}
		
		public boolean contains(ForwardDeadReckonPropositionCrossReferenceInfo info)
		{
			return contents.get(info.index);
		}
		
		public void xor(InternalMachineState other)
		{
			contents.xor(other.contents);
		}
		
		public MachineState getMachineState()
		{
			ProfileSection methodSection = new ProfileSection("InternalMachineState.getMachineState");
			try
			{
				MachineState result = new MachineState(new HashSet<GdlSentence>());
				
				for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i+1))
				{
					result.getContents().add(infoSet[i].sentence);
				}
				
				return result;
			}
			finally
			{
				methodSection.exitScope();
			}
		}
		
		@Override
		public String toString()
		{
			StringBuilder sb = new StringBuilder();
			boolean first = true;
			
			sb.append("( ");
			for (int i = contents.nextSetBit(0); i >= 0; i = contents.nextSetBit(i+1))
			{
				if ( !first )
				{
					sb.append(", ");
				}
				sb.append(infoSet[i].sentence);
				first = false;
			}
			sb.append(" )");
			
			return sb.toString();
		}

		@Override
		public Iterator<ForwardDeadReckonPropositionCrossReferenceInfo> iterator() {
			// TODO Auto-generated method stub
			return new InternalMachineStateIterator(this);
		}
	}
	
    /** The underlying proposition network  */
    private ForwardDeadReckonPropNet propNetX = null;
    private ForwardDeadReckonPropNet propNetO = null;
    private ForwardDeadReckonPropNet propNet = null;
    private PropNet basicPropNet;
    private Map<Role,PolymorphicComponent[]> legalPropositionsX = null;
    private Map<Role,Move[]> legalPropositionMovesX = null;
    private Map<Role,PolymorphicComponent[]> legalPropositionsO = null;
    private Map<Role,Move[]> legalPropositionMovesO = null;
    private Map<Role,PolymorphicComponent[]> legalPropositions = null;
    private Map<Role,Move[]> legalPropositionMoves = null;
    /** The player roles */
    private List<Role> roles;
    private InternalMachineState lastInternalSetStateX = null;
    private InternalMachineState lastInternalSetStateO = null;
    private InternalMachineState lastInternalSetState = null;
    private final boolean useSampleOfKnownLegals = false;
    private boolean useDeadReckonerForLegal = false;
    private MachineState lastPropnetSetState = null;
    private GdlSentence XSentence = null;
    private MachineState initialState = null;
    private PolymorphicProposition[] moveProps = null;
    private boolean measuringBasePropChanges = false;
    private Map<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> basePropChangeCounts = new HashMap<ForwardDeadReckonPropositionCrossReferenceInfo, Integer>();
    private PolymorphicProposition[] chosenJointMoveProps = null;
    private PolymorphicProposition[] previouslyChosenJointMovePropsX = null;
    private PolymorphicProposition[] previouslyChosenJointMovePropsO = null;
    private Map<Role,MoveInputSet> legalMoveInputPropositions = null;
    private int numLegalsFound;
    private ForwardDeadReckonPropositionCrossReferenceInfo[] masterInfoSet = null;
    
	public long totalNumGatesPropagated = 0;
	public long totalNumPropagates = 0;

    private class TestPropnetStateMachineStats extends Stats
    {
    	private long totalResets;
    	private int  numStateSettings;
    	private long totalGets;
    	private int  numStateFetches;
    	private int numBaseProps;
    	private int numInputs;
    	private int numLegals;
    	
    	public TestPropnetStateMachineStats(int numBaseProps, int numInputs, int numLegals)
    	{
    		this.numBaseProps = numBaseProps;
    		this.numInputs = numInputs;
    		this.numLegals = numLegals;
    	}
		@Override
		public void clear() {
			totalResets = 0;
			numStateSettings = 0;
			totalGets = 0;
			numStateFetches = 0;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			
			sb.append("#base props: " + numBaseProps);
			sb.append("\n");
			sb.append("#inputs: " + numInputs);
			sb.append("\n");
			sb.append("#legals: " + numLegals);
			sb.append("\n");
			sb.append("#state sets: " + numStateSettings);
			sb.append("\n");
			if ( numStateSettings > 0 )
			{
				sb.append("Average #components reset per state set: " + totalResets/numStateSettings);
				sb.append("\n");
			}
			sb.append("#state gets: " + numStateFetches);
			sb.append("\n");
			if ( numStateFetches > 0 )
			{
				sb.append( "Average #components queried per state get: " + totalGets/numStateFetches);
				sb.append("\n");
			}
			
			return sb.toString();
		}
		
		public void addResetCount(int resetCount)
		{
			numStateSettings++;
			totalResets += resetCount;
		}
		
		public void addGetCount(int getCount)
		{
			numStateFetches++;
			totalGets += getCount;
		}
    }
    
    private TestPropnetStateMachineStats stats;
    
    public Stats getStats()
    {
    	return stats;
    }
    
    /**
     * Initializes the PropNetStateMachine. You should compute the topological
     * ordering here. Additionally you may compute the initial state here, at
     * your discretion.
     */
    @SuppressWarnings("unused")
	@Override
    public void initialize(List<Gdl> description)
    {
		setRandomSeed(1);
		
    	try
    	{
    		ForwardDeadReckonPropNet fullPropNet = (ForwardDeadReckonPropNet) OptimizingPolymorphicPropNetFactory.create(description, new ForwardDeadReckonComponentFactory());
			fullPropNet.renderToFile("c:\\temp\\propnet.dot");
            
    		OptimizingPolymorphicPropNetFactory.removeAnonymousPropositions(fullPropNet);
    		OptimizingPolymorphicPropNetFactory.removeUnreachableBasesAndInputs(fullPropNet);
    		OptimizingPolymorphicPropNetFactory.removeRedundantConstantsAndGates(fullPropNet);
            
    		fullPropNet.renderToFile("c:\\temp\\propnetReduced.dot");
            
            roles = fullPropNet.getRoles();
    		moveProps = new PolymorphicProposition[roles.size()];
    		chosenJointMoveProps = new PolymorphicProposition[roles.size()];
    		previouslyChosenJointMovePropsX = new PolymorphicProposition[roles.size()];
    		previouslyChosenJointMovePropsO = new PolymorphicProposition[roles.size()];
    		legalMoveInputPropositions = new HashMap<Role,MoveInputSet>();
            stats = new TestPropnetStateMachineStats(fullPropNet.getBasePropositions().size(), fullPropNet.getInputPropositions().size(), fullPropNet.getLegalPropositions().get(getRoles().get(0)).length);
            //	Assess network statistics
            int numInputs = 0;
            int numMultiInputs = 0;
            int numMultiInputComponents = 0;
            
    		for(PolymorphicComponent c : fullPropNet.getComponents())
    		{
    			int n = c.getInputs().size();
    			
    			numInputs += n;
    			
    			if ( n > 1 )
    			{
    				numMultiInputComponents++;
    				numMultiInputs += n;
    			}
    		}
    		
    		int numComponents = fullPropNet.getComponents().size();
    		System.out.println("Num components: " + numComponents + " with an average of " + numInputs/numComponents + " inputs.");
    		System.out.println("Num multi-input components: " + numMultiInputComponents + " with an average of " + (numMultiInputComponents == 0 ? "N/A" : numMultiInputs/numMultiInputComponents) + " inputs.");
    		
    		masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet.getBasePropositions().size()];
    		int index = 0;
			for(Entry<GdlSentence,PolymorphicProposition> e : fullPropNet.getBasePropositions().entrySet())
			{
				ForwardDeadReckonProposition prop = (ForwardDeadReckonProposition)e.getValue();
				ForwardDeadReckonPropositionCrossReferenceInfo info = new ForwardDeadReckonPropositionCrossReferenceInfo();
				
				info.sentence = e.getKey();
				info.xNetProp = prop;
				info.oNetProp = prop;
				info.index = index;
				
				masterInfoSet[index++] = info;
				
				prop.setCrossReferenceInfo(info);
            	basePropChangeCounts.put(info,  0);
			}
    		fullPropNet.crystalize();
       		useDeadReckonerForLegal = fullPropNet.useDeadReckonerForLegal();
    		if ( !useDeadReckonerForLegal )
    		{
	    		legalPropositions = new HashMap<Role,PolymorphicComponent[]>();
	    		legalPropositionMoves = new HashMap<Role, Move[]>();
	    		for(Role role : getRoles())
	    		{
		    		PolymorphicComponent legalPropositionsForRole[] = new PolymorphicComponent[fullPropNet.getLegalPropositions().get(role).length];
		    		Move legalPropositionNamesForRole[] = new Move[fullPropNet.getLegalPropositions().get(role).length];
		    		index = 0;
		    		for(PolymorphicProposition p : fullPropNet.getLegalPropositions().get(role))
		    		{
		    			legalPropositionsForRole[index] = p;
		    			legalPropositionNamesForRole[index++] = new Move(p.getName().getBody().get(1));
		    		}
		    		
		    		legalPropositions.put(role, legalPropositionsForRole);
		    		legalPropositionMoves.put(role, legalPropositionNamesForRole);    		
	    		}
    		}

    		for(Role role : getRoles())
    		{
    			MoveInputSet inputSetForRole = new MoveInputSet();
    			inputSetForRole.moveInputPropositions = new PolymorphicProposition[fullPropNet.getLegalPropositions().get(role).length];
	    		
	    		legalMoveInputPropositions.put(role, inputSetForRole);
    		}

    		fullPropNet.reset(false);
    		fullPropNet.getInitProposition().setValue(true);
    		fullPropNet.propagate();
    		propNet = fullPropNet;
            initialState = getInternalStateFromBase().getMachineState();
    		fullPropNet.reset(true);
            
            measuringBasePropChanges = true;

			try {
				for(int i = 0; i < 10; i++)
				{
					performDepthCharge(initialState,null);
				}
			} catch (TransitionDefinitionException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			} catch (MoveDefinitionException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			measuringBasePropChanges = false;
			
			int highestCount = 0;
			for(Entry<ForwardDeadReckonPropositionCrossReferenceInfo, Integer> e : basePropChangeCounts.entrySet())
			{
				if ( e.getValue() > highestCount )
				{
					highestCount = e.getValue();
					XSentence = e.getKey().sentence;
				}
			}
            
			basePropChangeCounts = null;
    		lastInternalSetState = null;
    		propNet = null;

    		propNetX = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
    		propNetO = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
			propNetX.RemoveInits();
			propNetO.RemoveInits();

			if ( XSentence != null )
			{
				System.out.println("Reducing with respect to XSentence: " + XSentence);
				GdlSentence possibleOSentence = null;
				OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, XSentence, true);
				
				//	If the reduced net always transitions it's own hard-wired sentence into the opposite state
				//	it may be part of a pivot whereby control passes between alternating propositions.  Check this
				//	Do we turn something else on unconditionally?
				for(Entry<GdlSentence,PolymorphicProposition> e : propNetX.getBasePropositions().entrySet())
				{
					PolymorphicComponent input = e.getValue().getSingleInput();
					
					if ( input instanceof PolymorphicTransition )
					{
						PolymorphicComponent driver = input.getSingleInput();
						
						if ( driver instanceof PolymorphicConstant && driver.getValue())
						{
							//	Found a suitable candidate
							possibleOSentence = e.getKey();
							break;
						}
					}
				}
				
				if ( possibleOSentence != null )
				{
					System.out.println("Possible OSentence: " + possibleOSentence);
					OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, possibleOSentence, true);
					
					//	Does this one turn the original back on?
					PolymorphicProposition originalPropInSecondNet = propNetO.getBasePropositions().get(XSentence);
					if ( originalPropInSecondNet != null )
					{
						PolymorphicComponent input = originalPropInSecondNet.getSingleInput();
						
						if ( input instanceof PolymorphicTransition )
						{
							PolymorphicComponent driver = input.getSingleInput();
							
							if ( !(driver instanceof PolymorphicConstant) || !driver.getValue())
							{
								//	Nope - doesn't work
								possibleOSentence = null;
								System.out.println("Fails to recover back-transition to " + XSentence);
							}
						}
					}
					
					if ( possibleOSentence != null )
					{
						//	So if we set the first net's trigger condition to off in the second net do we find
						//	the second net's own trigger is always off?
						OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
						
						PolymorphicProposition OSentenceInSecondNet = propNetO.getBasePropositions().get(possibleOSentence);
						if ( OSentenceInSecondNet != null )
						{
							PolymorphicComponent input = OSentenceInSecondNet.getSingleInput();
							
							if ( input instanceof PolymorphicTransition )
							{
								PolymorphicComponent driver = input.getSingleInput();
								
								if ( !(driver instanceof PolymorphicConstant) || driver.getValue())
								{
									//	Nope - doesn't work
									System.out.println("Fails to recover back-transition remove of " + possibleOSentence);
									possibleOSentence = null;
								}
								
								//	Finally, if we set the OSentence off in the first net do we recover the fact that
								//	the XSentence always moves to off in transitions from the first net?
								if ( possibleOSentence != null )
								{
									OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, possibleOSentence, false);
									
									PolymorphicProposition XSentenceInFirstNet = propNetX.getBasePropositions().get(XSentence);
									if ( XSentenceInFirstNet != null )
									{
										input = XSentenceInFirstNet.getSingleInput();
										
										if ( input instanceof PolymorphicTransition )
										{
											driver = input.getSingleInput();
											
											if ( !(driver instanceof PolymorphicConstant) || driver.getValue())
											{
												//	Nope - doesn't work
												System.out.println("Fails to recover removal of " + XSentence);
												possibleOSentence = null;
											}
										}
									}
								}
							}
						}
						else
						{
							possibleOSentence = null;
						}
					}
				}
				
				if (possibleOSentence == null)
				{
					System.out.println("Reverting OSentence optimizations");
					//	Failed - best we can do is simply drive the XSentence to true in one network
		    		propNetX = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
		    		propNetO = new ForwardDeadReckonPropNet(fullPropNet, new ForwardDeadReckonComponentFactory());
					propNetX.RemoveInits();
					propNetO.RemoveInits();
					OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetX, XSentence, true);
					OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
				}
				
				//OptimizingPolymorphicPropNetFactory.fixBaseProposition(propNetO, XSentence, false);
				propNetX.renderToFile("c:\\temp\\propnetReducedX.dot");
	    		propNetO.renderToFile("c:\\temp\\propnetReducedO.dot");
	    		System.out.println("Num components remaining in X-net: " + propNetX.getComponents().size());
	    		System.out.println("Num components remaining in O-net: " + propNetO.getComponents().size());
			}
			
    		masterInfoSet = new ForwardDeadReckonPropositionCrossReferenceInfo[fullPropNet.getBasePropositions().size()];
    		index = 0;
			//	Cross-reference the base propositions of the two networks
			for(Entry<GdlSentence,PolymorphicProposition> e : propNetX.getBasePropositions().entrySet())
			{
				ForwardDeadReckonProposition oProp = (ForwardDeadReckonProposition) propNetO.getBasePropositions().get(e.getKey());
				ForwardDeadReckonProposition xProp = (ForwardDeadReckonProposition)e.getValue();
				ForwardDeadReckonPropositionCrossReferenceInfo info = new ForwardDeadReckonPropositionCrossReferenceInfo();
				
				info.sentence = e.getKey();
				info.xNetProp = xProp;
				info.oNetProp = oProp;
				info.index = index;
				
				masterInfoSet[index++] = info;
				
				xProp.setCrossReferenceInfo(info);
				oProp.setCrossReferenceInfo(info);
			}
			
    		propNetX.crystalize();
    		propNetO.crystalize();
    		
    		propNetX.reset(true);
    		propNetO.reset(true);
   		
    		useDeadReckonerForLegal = propNetX.useDeadReckonerForLegal();
    		if ( !useDeadReckonerForLegal )
    		{
	    		legalPropositionsX = new HashMap<Role,PolymorphicComponent[]>();
	    		legalPropositionMovesX = new HashMap<Role, Move[]>();
	    		for(Role role : getRoles())
	    		{
		    		PolymorphicComponent legalPropositionsForRole[] = new PolymorphicComponent[propNetX.getLegalPropositions().get(role).length];
		    		Move legalPropositionNamesForRole[] = new Move[propNetX.getLegalPropositions().get(role).length];
		    		index = 0;
		    		for(PolymorphicProposition p : propNetX.getLegalPropositions().get(role))
		    		{
		    			legalPropositionsForRole[index] = p;
		    			legalPropositionNamesForRole[index++] = new Move(p.getName().getBody().get(1));
		    		}
		    		
		    		legalPropositionsX.put(role, legalPropositionsForRole);
		    		legalPropositionMovesX.put(role, legalPropositionNamesForRole);    		
	    		}
	    		
	    		legalPropositionsO = new HashMap<Role,PolymorphicComponent[]>();
	    		legalPropositionMovesO = new HashMap<Role, Move[]>();
	    		for(Role role : getRoles())
	    		{
		    		PolymorphicComponent legalPropositionsForRole[] = new PolymorphicComponent[propNetO.getLegalPropositions().get(role).length];
		    		Move legalPropositionNamesForRole[] = new Move[propNetO.getLegalPropositions().get(role).length];
		    		index = 0;
		    		for(PolymorphicProposition p : propNetO.getLegalPropositions().get(role))
		    		{
		    			legalPropositionsForRole[index] = p;
		    			legalPropositionNamesForRole[index++] = new Move(p.getName().getBody().get(1));
		    		}
		    		
		    		legalPropositionsO.put(role, legalPropositionsForRole);
		    		legalPropositionMovesO.put(role, legalPropositionNamesForRole);    		
	    		}
    		}
    		
			propNet = propNetX;
		    legalPropositions = legalPropositionsX;
		    legalPropositionMoves = legalPropositionMovesX;
   		}
    	catch (InterruptedException e)
    	{
			// TODO: handle exception
		}        
    }   

    public void Optimize()
    {
		for(PolymorphicComponent c : propNet.getComponents())
		{
			((LearningComponent) c).Optimize();
		}
    }
    
    private void setBasePropositionsFromState(MachineState state)
    {
    	setBasePropositionsFromState(new InternalMachineState(masterInfoSet, XSentence, state));
    }
    
    private void setBasePropositionsFromState(InternalMachineState state)
    {
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.setBasePropositionsInternal");
		try
		{
			//System.out.println("Last set state was: " + lastSetState);
			if ( lastInternalSetState != state )
			{
				if ( lastInternalSetState != null )
				{
					lastInternalSetState.xor(state);
					for(ForwardDeadReckonPropositionCrossReferenceInfo info : lastInternalSetState)
					{
						if (state.contains(info))
						{
							if ( propNet == propNetX )
							{
								info.xNetProp.setValue(true);
							}
							else
							{
								info.oNetProp.setValue(true);
							}
						}
						else
						{
							if ( propNet == propNetX )
							{
								info.xNetProp.setValue(false);
							}
							else
							{
								info.oNetProp.setValue(false);
							}
						}
						
						if (measuringBasePropChanges)
						{
							basePropChangeCounts.put(info, basePropChangeCounts.get(info)+1);
						}
					}
					if ( false)
					{
						for(ForwardDeadReckonPropositionCrossReferenceInfo info : state)
						{
							if ( !lastInternalSetState.contains(info) )
							{
								//System.out.println("Changing prop " + s + " to true");
								if ( propNet == propNetX )
								{
									info.xNetProp.setValue(true);
								}
								else
								{
									info.oNetProp.setValue(true);
								}
								
								if (measuringBasePropChanges)
								{
									basePropChangeCounts.put(info, basePropChangeCounts.get(info)+1);
								}
							}
						}
						for(ForwardDeadReckonPropositionCrossReferenceInfo info : lastInternalSetState)
						{
							if ( !state.contains(info) )
							{
								//System.out.println("Changing prop " + s + " to false");
								if ( propNet == propNetX )
								{
									info.xNetProp.setValue(false);
								}
								else
								{
									info.oNetProp.setValue(false);
								}
								
								if (measuringBasePropChanges)
								{
									basePropChangeCounts.put(info, basePropChangeCounts.get(info)+1);
								}
							}
						}
					}
				}
				else
				{
					//System.out.println("Setting entire state");
					for (PolymorphicProposition p : propNet.getBasePropositionsArray())
					{
						p.setValue(false);
					}
					for(ForwardDeadReckonPropositionCrossReferenceInfo s : state)
					{
						if ( propNet == propNetX )
						{
							s.xNetProp.setValue(true);
						}
						else
						{
							s.oNetProp.setValue(true);
						}
					}
				}
				
				lastInternalSetState = state;
			}
		}
		finally
		{
			methodSection.exitScope();
		}
    }
    
	/**
	 * Computes if the state is terminal. Should return the value
	 * of the terminal proposition for the state.
	 */
	@Override
	public boolean isTerminal(MachineState state)
	{
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.isTerminal");
		try
		{
			setPropNetUsage(state);
			setBasePropositionsFromState(state);
			
			PolymorphicProposition terminalProp = propNet.getTerminalProposition();
			boolean result = terminalProp.getSingleInput().getValue();
			//if ( result )
			//{
			//	System.out.println("State " + state + " is terminal");
			//}
			
			return result;
		}
		finally
		{
			methodSection.exitScope();
		}
	}
	
	public boolean isTerminal()
	{
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.isTerminal");
		try
		{
			PolymorphicProposition terminalProp = propNet.getTerminalProposition();
			boolean result = terminalProp.getSingleInput().getValue();
			//if ( result )
			//{
			//	System.out.println("State " + state + " is terminal");
			//}
			
			return result;
		}
		finally
		{
			methodSection.exitScope();
		}
	}

	/**
	 * Computes the goal for a role in the current state.
	 * Should return the value of the goal proposition that
	 * is true for that role. If there is not exactly one goal
	 * proposition true for that role, then you should throw a
	 * GoalDefinitionException because the goal is ill-defined. 
	 */
	@Override
	public int getGoal(MachineState state, Role role)
	throws GoalDefinitionException
	{
		//ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.getGoal");
		//try
		//{
			setPropNetUsage(state);
			setBasePropositionsFromState(state);
			
			PolymorphicProposition[] goalProps = propNet.getGoalPropositions().get(role);
			int result = 0;
			
			for(PolymorphicProposition p : goalProps)
			{
				PolymorphicComponent goalTransition = p.getSingleInput();
				if ( goalTransition != null && goalTransition.getValue())
				{
					result = Integer.parseInt(p.getName().getBody().get(1).toString());
					break;
				}
			}
			
			return result;
		//}
		//finally
		//{
		//	methodSection.exitScope();
		//}
	}

	private int getGoal(Role role)
	throws GoalDefinitionException
	{
		PolymorphicProposition[] goalProps = propNet.getGoalPropositions().get(role);
		int result = 0;
		
		for(PolymorphicProposition p : goalProps)
		{
			PolymorphicComponent goalTransition = p.getSingleInput();
			if ( goalTransition != null && goalTransition.getValue())
			{
				result = Integer.parseInt(p.getName().getBody().get(1).toString());
				break;
			}
		}
		
		return result;
	}
	
	/**
	 * Returns the initial state. The initial state can be computed
	 * by only setting the truth value of the INIT proposition to true,
	 * and then computing the resulting state.
	 */
	@Override
	public MachineState getInitialState()
	{
		//System.out.println("Initial state: " + result);
        return initialState;
	}
	
	/**
	 * Computes the legal moves for role in state.
	 */
	@Override
	public List<Move> getLegalMoves(MachineState state, Role role)
	throws MoveDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.getLegalMoves");
		try
		{
			List<Move> result;
			
			setPropNetUsage(state);
			setBasePropositionsFromState(state);
			
			//ForwardDeadReckonComponent.numGatesPropagated = 0;
			//ForwardDeadReckonComponent.numPropagates = 0;
			//propNet.seq++;
			
			if ( useDeadReckonerForLegal )
			{
				result = new LinkedList<Move>();
				for(ForwardDeadReckonLegalMoveInfo moveInfo : propNet.getActiveLegalProps().get(role))
				{
					result.add(moveInfo.move);
				}
			}
			else
			{
				result = new LinkedList<Move>();
				PolymorphicComponent[] legalTransitions = legalPropositions.get(role);
				Move[] legalPropMoves = legalPropositionMoves.get(role);
				int numProps = legalTransitions.length;
				
				for(int i = 0; i < numProps; i++)
				{
					if ( legalTransitions[i].getValue())
					{
						result.add(legalPropMoves[i]);
					}
				}
			}
			
			//totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
			//totalNumPropagates += ForwardDeadReckonComponent.numPropagates;
			
			//System.out.println("legals for role " + role + ": " + result);
			return result;
		}
		finally
		{
			methodSection.exitScope();
		}
	}
	
	private void setupLegalMoveInputPropositions()
	throws MoveDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.setupLegalMoveInputProps");
		try
		{
			//setPropNetUsage(state);
			//setBasePropositionsFromState(state, false);
			
			for(Role role : getRoles())
			{
				MoveInputSet inputSet = legalMoveInputPropositions.get(role);
				inputSet.numPropositions = 0;
				
				if ( useDeadReckonerForLegal )
				{
					for(ForwardDeadReckonLegalMoveInfo moveInfo : propNet.getActiveLegalProps().get(role))
					{
						inputSet.moveInputPropositions[inputSet.numPropositions++] = moveInfo.inputProposition;
					}
				}
				else
				{
					PolymorphicComponent[] legalProps = legalPropositions.get(role);
					int numProps = legalProps.length;
					
					for(int i = 0; i < numProps; i++)
					{
						if ( legalProps[i].getValue())
						{
							inputSet.moveInputPropositions[inputSet.numPropositions++] = propNet.getLegalInputMap().get(legalProps[i]);
						}
					}
				}
			}
		}
		finally
		{
			methodSection.exitScope();
		}
	}
	
	/**
	 * Checks whether a specified move is legal for role in state.
	 */
	public boolean isLegalMove(MachineState state, Role role, Move move)
			throws MoveDefinitionException
	{
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.isLegalMove");
		try
		{
			setPropNetUsage(state);
			setBasePropositionsFromState(state);
			
			Map<GdlSentence, PolymorphicProposition> inputProps = propNet.getInputPropositions();
			
			GdlSentence moveSentence = ProverQueryBuilder.toDoes(role, move);
			PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
			PolymorphicProposition legalProp = propNet.getLegalInputMap().get(moveInputProposition);
			if ( legalProp != null )
			{
				return legalProp.getSingleInput().getValue();
			}
			
			throw new MoveDefinitionException(state,role);
		}
		finally
		{
			methodSection.exitScope();
		}
	}
	
	private void setPropNetUsage(MachineState state)
	{
		setPropNetUsage(new InternalMachineState(masterInfoSet, XSentence, state));
	}
	
	private void setPropNetUsage(InternalMachineState state)
	{
		//System.out.println("setPropNetUsage for state: " + state);
		if ( XSentence != null )
		{
			if ( state.isXState )
			{
				if ( propNet != propNetX )
				{
					//System.out.println("Switching (internal) to machine X in state: " + state);
					propNet = propNetX;
				    legalPropositions = legalPropositionsX;
				    legalPropositionMoves = legalPropositionMovesX;
				    
				    lastInternalSetStateO = lastInternalSetState;
				    lastInternalSetState = lastInternalSetStateX;
				}
			}
			else
			{
				if ( propNet != propNetO )
				{
					//System.out.println("Switching (internal) to machine O in state: " + state);
					propNet = propNetO;
				    legalPropositions = legalPropositionsO;
				    legalPropositionMoves = legalPropositionMovesO;
				    
				    lastInternalSetStateX = lastInternalSetState;
				    lastInternalSetState = lastInternalSetStateO;
				}
			}
		}
	}
	/**
	 * Computes the next state given state and the list of moves.
	 */
	@Override
	public MachineState getNextState(MachineState state, List<Move> moves)
	throws TransitionDefinitionException
	{
		//System.out.println("Get next state after " + moves + " from: " + state);
		//RuntimeOptimizedComponent.getCount = 0;
		//RuntimeOptimizedComponent.dirtyCount = 0;
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.getNextState");
		try
		{
			setPropNetUsage(state);
			//for(PolymorphicComponent c : propNet.getComponents())
			//{
			//	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
			//}
			//ForwardDeadReckonComponent.numGatesPropagated = 0;
			//ForwardDeadReckonComponent.numPropagates = 0;
			//propNet.seq++;
			
			Map<GdlSentence, PolymorphicProposition> inputProps = propNet.getInputPropositions();
			int movesCount = 0;
			
			for(GdlSentence moveSentence : toDoes(moves))
			{
				PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
				if ( moveInputProposition != null )
				{
					moveInputProposition.setValue(true);
					moveProps[movesCount++] = moveInputProposition;
				}
			}
			
			setBasePropositionsFromState(state);
			
			MachineState result = getInternalStateFromBase().getMachineState();
			
			//System.out.println("After move " + moves + " in state " + state + " resulting state is " + result);
			//totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
			//totalNumPropagates += ForwardDeadReckonComponent.numPropagates;
	
			for(int i = 0; i < movesCount; i++)
			{
				moveProps[i].setValue(false);
			}
			//for(GdlSentence moveSentence : toDoes(moves))
			//{
			//	PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
			//	if ( moveInputProposition != null )
			//	{
			//		moveInputProposition.setValue(false);
			//	}
			//}
	        
	        return result;
		}
		finally
		{
			methodSection.exitScope();
		}
	}

	private void transitionToNextStateFromChosenMove()
	{
		//System.out.println("Get next state after " + moves + " from: " + state);
		//RuntimeOptimizedComponent.getCount = 0;
		//RuntimeOptimizedComponent.dirtyCount = 0;
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.getNextStateFromChosen");
		try
		{
			//for(PolymorphicComponent c : propNet.getComponents())
			//{
			//	((ForwardDeadReckonComponent)c).hasQueuedForPropagation = false;
			//}
			//ForwardDeadReckonComponent.numGatesPropagated = 0;
			//ForwardDeadReckonComponent.numPropagates = 0;
			//propNet.seq++;
			
			int index = 0;
			for(PolymorphicProposition moveProp : chosenJointMoveProps)
			{
				PolymorphicProposition previousChosenMove;
				
				if ( propNet == propNetX )
				{
					previousChosenMove = previouslyChosenJointMovePropsX[index];
				}
				else
				{
					previousChosenMove = previouslyChosenJointMovePropsO[index];
				}
				if ( moveProp != null )
				{
					moveProp.setValue(true);
					//System.out.println("Move: " + moveProp.getName());
				}
				if ( previousChosenMove != null && previousChosenMove != moveProp )
				{
					previousChosenMove.setValue(false);
				}
				if ( propNet == propNetX )
				{
					previouslyChosenJointMovePropsX[index++] = moveProp;
				}
				else
				{
					previouslyChosenJointMovePropsO[index++] = moveProp;
				}
			}
			
			
			//setBasePropositionsFromState(state, true);
			
			InternalMachineState result = getInternalStateFromBase();
			//System.out.println("Transitioning to state: " + result);
			
			//System.out.println("After move resulting state is " + result);
			//totalNumGatesPropagated += ForwardDeadReckonComponent.numGatesPropagated;
			//totalNumPropagates += ForwardDeadReckonComponent.numPropagates;
	
			//for(PolymorphicProposition moveProp : chosenJointMoveProps)
			//{
			//	if ( moveProp != null )
			//	{
			//		moveProp.setValue(false);
			//	}
			//}
			//for(GdlSentence moveSentence : toDoes(moves))
			//{
			//	PolymorphicProposition moveInputProposition = inputProps.get(moveSentence);
			//	if ( moveInputProposition != null )
			//	{
			//		moveInputProposition.setValue(false);
			//	}
			//}
			setPropNetUsage(result);
			setBasePropositionsFromState(result);
		}
		finally
		{
			methodSection.exitScope();
		}
	}

	/* Already implemented for you */
	@Override
	public List<Role> getRoles() {
		return roles;
	}

	/* Helper methods */
		
	/**
	 * The Input propositions are indexed by (does ?player ?action).
	 * 
	 * This translates a list of Moves (backed by a sentence that is simply ?action)
	 * into GdlSentences that can be used to get Propositions from inputPropositions.
	 * and accordingly set their values etc.  This is a naive implementation when coupled with 
	 * setting input values, feel free to change this for a more efficient implementation.
	 * 
	 * @param moves
	 * @return
	 */
	private List<GdlSentence> toDoes(List<Move> moves)
	{
		List<GdlSentence> doeses = new ArrayList<GdlSentence>(moves.size());
		Map<Role, Integer> roleIndices = getRoleIndices();
		
		for (int i = 0; i < roles.size(); i++)
		{
			int index = roleIndices.get(roles.get(i));
			doeses.add(ProverQueryBuilder.toDoes(roles.get(i), moves.get(index)));
		}
		return doeses;
	}
	
	private InternalMachineState getInternalStateFromBase()
	{
		ProfileSection methodSection = new ProfileSection("TestPropnetStateMachine.getStateFromBase");
		try
		{
			//RuntimeOptimizedComponent.getCount = 0;
			
			InternalMachineState result = new InternalMachineState(masterInfoSet);
			for(ForwardDeadReckonPropositionCrossReferenceInfo info : propNet.getActiveBaseProps())
			{
				result.add(info);
				
				if ( info.sentence == XSentence )
				{
					result.isXState = true;
				}
			}
			
			return result;
			//Set<GdlSentence> contents = new HashSet<GdlSentence>();
			//nt numBaseProps = basePropositionTransitions.length;
			
			//for (int i = 0; i < numBaseProps; i++)
			//{
			//	PolymorphicComponent t = basePropositionTransitions[i];
			//	if (t.getValue())
			//	{
			//		contents.add(propNet.getBasePropositionsArray()[i].getName());
			//	}
			//}
			//stats.addGetCount(RuntimeOptimizedComponent.getCount);
			
			//return new MachineState(contents);
		}
		finally
		{
			methodSection.exitScope();
		}
	}

	private Map<Role,List<Move>> recentLegalMoveSetsList = new HashMap<Role,List<Move>>();

	@Override
    public Move getRandomMove(MachineState state, Role role) throws MoveDefinitionException
    {
		if ( useSampleOfKnownLegals )
		{
			int choiceSeed = getRandom(100);
			final int tryPreviousPercentage = 80;
			List<Move> previouslyAvailableMoves = null;
			boolean preferNew = false;
			
			if ( choiceSeed < tryPreviousPercentage && recentLegalMoveSetsList.keySet().contains(role))
			{
				previouslyAvailableMoves = recentLegalMoveSetsList.get(role);
				Move result = previouslyAvailableMoves.get(getRandom(previouslyAvailableMoves.size()));
				
				if ( isLegalMove(state, role, result))
				{
					return result;
				}
			}
			else if ( choiceSeed > 100 - tryPreviousPercentage/2 )
			{
				preferNew = true;
			}
			
	        List<Move> legals = getLegalMoves(state, role);
	        List<Move> candidates;
	        
	        if ( preferNew && previouslyAvailableMoves != null )
	        {
	        	candidates = new LinkedList<Move>();
	        	
	        	for(Move move : legals)
	        	{
	        		if ( !previouslyAvailableMoves.contains(move))
	        		{
	        			candidates.add(move);
	        		}
	        	}
	        }
	        else
	        {
	        	candidates = legals;
	        }
	        
	        if ( legals.size() > 1 )
	        {
	        	recentLegalMoveSetsList.put(role, legals);
	        }
	        
	        return candidates.get(getRandom(candidates.size()));
	    }
		else
		{
	        List<Move> legals = getLegalMoves(state, role);
	        
	        int randIndex = getRandom(legals.size());
	        return legals.get(randIndex);
		}
    }

	private void chooseRandomJointMove() throws MoveDefinitionException
	{
		setupLegalMoveInputPropositions();
		
		int index = 0;
        for (Role role : getRoles())
        {
        	MoveInputSet set = legalMoveInputPropositions.get(role);
        	chosenJointMoveProps[index++] = set.moveInputPropositions[getRandom(set.numPropositions)];
        }
		
	}
  
    public int getDepthChargeResult(MachineState state, Role role, final int[] theDepth) throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException {  
        int nDepth = 0;
		setPropNetUsage(state);
		setBasePropositionsFromState(state);
        for(int i = 0; i < roles.size(); i++)
        {
        	previouslyChosenJointMovePropsX[i] = null;
        	previouslyChosenJointMovePropsO[i] = null;
        }
        while(!isTerminal()) {
            nDepth++;
            chooseRandomJointMove();
            transitionToNextStateFromChosenMove();
        }
        if(theDepth != null)
            theDepth[0] = nDepth;
        for(int i = 0; i < roles.size(); i++)
        {
        	if ( previouslyChosenJointMovePropsX[i] != null)
        	{
        		previouslyChosenJointMovePropsX[i].setValue(false);
        	}
        	if ( previouslyChosenJointMovePropsO[i] != null)
        	{
        		previouslyChosenJointMovePropsO[i].setValue(false);
        	}
        }
        return getGoal(role);
    }
}
