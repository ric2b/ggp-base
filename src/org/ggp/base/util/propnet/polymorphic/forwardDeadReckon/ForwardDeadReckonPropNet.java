package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.profile.ProfileSection;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponentFactory;
import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

public class ForwardDeadReckonPropNet extends PolymorphicPropNet {
	/**
	 * Creates a new PropNet from a list of Components, along with indices over
	 * those components.
	 * 
	 * @param components
	 *            A list of Components.
	 */
	private ForwardDeadReckonComponent[] propagationQueue = null;
	private ForwardDeadReckonComponent[] alternatePropagationQueue = null;
	private int propagationQueueIndex;
	private Map<Role,Set<Move>> activeLegalMoves;
	private Set<GdlSentence> activeBasePropositions;
	private Set<GdlSentence> alwaysTrueBasePropositions;
	private boolean useDeadReckonerForLegal;
	private final int legalPropsPerRoleThreasholdForDeadReckon = 20;
	
	public ForwardDeadReckonPropNet(PropNet sourcePropnet, PolymorphicComponentFactory componentFactory)
	{
		super(sourcePropnet, componentFactory);
		
		propagationQueue = new ForwardDeadReckonComponent[getComponents().size()];
		alternatePropagationQueue = new ForwardDeadReckonComponent[getComponents().size()];
		propagationQueueIndex = 0;
	}
	
	public ForwardDeadReckonPropNet(PolymorphicPropNet sourcePropnet, PolymorphicComponentFactory componentFactory)
	{
		super(sourcePropnet, componentFactory);
		
		propagationQueue = new ForwardDeadReckonComponent[getComponents().size()];
		alternatePropagationQueue = new ForwardDeadReckonComponent[getComponents().size()];
		propagationQueueIndex = 0;
	}
	
	public ForwardDeadReckonPropNet(List<Role> roles, Set<PolymorphicComponent> components, PolymorphicComponentFactory componentFactory)
	{
		super(roles, components, componentFactory);
		
		propagationQueue = new ForwardDeadReckonComponent[getComponents().size()];
		alternatePropagationQueue = new ForwardDeadReckonComponent[getComponents().size()];
		propagationQueueIndex = 0;
	}
	
	private void setUpActivePropositionSets()
	{
		int numTotalLegalProps = 0;
		int numRoles = 0;
		
		for(Role role : getRoles())
		{
			numRoles++;
			numTotalLegalProps += getLegalPropositions().get(role).length;
		}
		useDeadReckonerForLegal = (numTotalLegalProps > numRoles*legalPropsPerRoleThreasholdForDeadReckon);
		if ( useDeadReckonerForLegal )
		{
			activeLegalMoves = new HashMap<Role,Set<Move>>();
			
			for(Role role : getRoles())
			{
				PolymorphicProposition[] legalProps = getLegalPropositions().get(role);
				Set<Move> activeLegalMovesForRole = new HashSet<Move>();
				
				for(PolymorphicProposition p : legalProps)
				{
					ForwardDeadReckonProposition pfdr = (ForwardDeadReckonProposition)p;
					
					pfdr.setTransitionSet(new Move(pfdr.getName().getBody().get(1)), activeLegalMovesForRole);
				}
				
				activeLegalMoves.put(role, activeLegalMovesForRole);
			}
		}
		
		activeBasePropositions = new HashSet<GdlSentence>();
		alwaysTrueBasePropositions = new HashSet<GdlSentence>();
		
		for(PolymorphicProposition p : getBasePropositions().values())
		{
			PolymorphicComponent input = p.getSingleInput();
			
			if ( input instanceof ForwardDeadReckonTransition)
			{
				ForwardDeadReckonTransition t = (ForwardDeadReckonTransition)input;
				
				t.setTransitionSet(p.getName(), activeBasePropositions);
			}
			else if ( input instanceof PolymorphicConstant )
			{
				if ( input.getValue() )
				{
					alwaysTrueBasePropositions.add(p.getName());
				}
			}
		}
	}
	
	@Override
	public void crystalize()
	{
		super.crystalize();
		
		for(PolymorphicComponent c : getComponents())
		{
			((ForwardDeadReckonComponent)c).setPropnet(this);
		}
		
		setUpActivePropositionSets();
	}
	
	public boolean useDeadReckonerForLegal()
	{
		return useDeadReckonerForLegal;
	}
	
	public Map<Role,Set<Move>> getActiveLegalProps()
	{
		return activeLegalMoves;
	}
	
	public Set<GdlSentence> getActiveBaseProps()
	{
		return activeBasePropositions;
	}
	
	public void reset(boolean fullEquilibrium)
	{
		activeBasePropositions.clear();
		activeBasePropositions.addAll(alwaysTrueBasePropositions);
		
		if ( activeLegalMoves != null )
		{
			for(Role role : getRoles())
			{
				activeLegalMoves.get(role).clear();
			}
		}
		for(PolymorphicComponent c : getComponents())
		{
			((ForwardDeadReckonComponent) c).reset();
		}
		//	Establish full reset state if required
		if ( fullEquilibrium )
		{
			for(PolymorphicComponent c : getComponents())
			{
				((ForwardDeadReckonComponent) c).queuePropagation();
			}
			propagate();
		}
	}
	
	public void addToPropagateQueue(ForwardDeadReckonComponent component)
	{
		propagationQueue[propagationQueueIndex++] = component;
	}
	
	private void validate()
	{
		for(PolymorphicComponent c : getComponents())
		{
			((ForwardDeadReckonComponent)c).validate();
		}
	}
	public void propagate()
	{
		ProfileSection methodSection = new ProfileSection("ForwardDeadReckonPropNet.propagate");
		try
		{
			while(propagationQueueIndex > 0)
			{
				//validate();
				
				ForwardDeadReckonComponent[] queue = propagationQueue;
				int queueSize = propagationQueueIndex;
				
				propagationQueue = alternatePropagationQueue;
				alternatePropagationQueue = queue;
				
				propagationQueueIndex = 0;
				
				for(int i = 0; i < queueSize; i++)
				{
					queue[i].propagate();
				}
			}
		}
		finally
		{
			methodSection.exitScope();
		}
	}
}
