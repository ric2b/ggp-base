package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicOr;

/**
 * The Or class is designed to represent logical OR gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedOr extends RuntimeOptimizedComponent implements PolymorphicOr
{
	PolymorphicComponent knownTrueInput = null;
	
	public static int numSwaps = 0;
	public static int totalSearchCount = 0;
	public static int numSearches = 0;
	
	private Map<PolymorphicComponent,Integer> successSearchCount = null;
	
	/**
	 * Returns true if and only if at least one of the inputs to the or is true.
	 * 
	 * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
	 */
	@Override
	protected boolean getValueInternal()
	{
		if ( unconditionalGet )
		{
			if ( inputsArray == null )
			{
				for ( PolymorphicComponent component : getInputs() )
				{
					if ( component.getValue() )
					{
						return true;
					}
				}
			}
			else
			{
				for ( PolymorphicComponent component : inputsArray )
				{
					if ( component.getValue() )
					{
						return true;
					}
				}
			}
			
			return false;
		}
		else
		{
			if ( learnMode )
			{
				if ( successSearchCount == null )
				{
					successSearchCount = new HashMap<PolymorphicComponent,Integer>();
				}
				
				for ( RuntimeOptimizedComponent component : inputs )
				{
					Integer count = 0;
					
					if ( successSearchCount.containsKey(component))
					{
						count = successSearchCount.get(component);
					}
					
					EncapsulatedCost cost = new EncapsulatedCost();
					if (component.getValueAndCost(cost))
					{
						successSearchCount.put(component, count-cost.getCost()+inputs.size()/2);
					}
					else
					{
						successSearchCount.put(component, count-cost.getCost());
					}
				}
			}
			
			boolean dirtyFound = false;
			
			knownTrueInput = null;
			//	See if we can find a result without further queries first by checking
			//	non-dirty inputs
			if ( inputsArray == null )
			{
				for ( RuntimeOptimizedComponent component : inputs )
				{
					if ( !component.isDirty() )
					{
						if (component.getValue() )
						{
							knownTrueInput = component;
							return true;
						}
					}
					else
					{
						dirtyFound = true;
					}
				}
			}
			else
			{
				for ( RuntimeOptimizedComponent component : inputsArray )
				{
					if ( !component.isDirty() )
					{
						if (component.getValue() )
						{
							knownTrueInput = component;
							return true;
						}
					}
					else
					{
						dirtyFound = true;
					}
				}
			}
			
			if (dirtyFound)
			{
				numSearches++;
				
				if ( inputsArray == null )
				{
					for ( RuntimeOptimizedComponent component : inputs )
					{
						totalSearchCount++;
							
						if ( component.getValue() )
						{
							numSwaps++;
							knownTrueInput = component;						
							return true;
						}
					}
				}
				else
				{
					for ( RuntimeOptimizedComponent component : inputsArray )
					{
						totalSearchCount++;
							
						if ( component.getValue() )
						{
							numSwaps++;
							knownTrueInput = component;						
							return true;
						}
					}
				}
			}
			
			return false;
		}
	}
	
	void refindKnownTrues()
	{
		knownTrueInput = null;
		
		if ( inputsArray == null )
		{
			for(RuntimeOptimizedComponent input : inputs)
			{
				if ( !input.isDirty() && input.getValue())
				{
					knownTrueInput = input;
					break;
				}
			}
		}
		else
		{
			for(RuntimeOptimizedComponent input : inputsArray)
			{
				if ( !input.isDirty() && input.getValue())
				{
					knownTrueInput = input;
					break;
				}
			}
		}
	}

    protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
    {
 		boolean dirtyFound = false;
		
		aggregatedCost.incrementCost();
 		
		if ( dirty )
		{
			//	See if we can find a result without further queries first by checking
			//	non-dirty inputs
			for ( RuntimeOptimizedComponent component : inputs )
			{
				if ( !component.isDirty() )
				{
					if (component.getValueAndCost(aggregatedCost))
					{
						return true;
					}
				}
				else
				{
					dirtyFound = true;
				}
			}
			
			if ( dirtyFound)
			{
				for ( RuntimeOptimizedComponent component : inputs )
				{
					if ( component.getValueAndCost(aggregatedCost) )
					{
						return true;
					}
				}
			}
			
			return false;
		}
		else
		{
			return cachedValue;
		}
    }

	@Override
    public void Optimize()
    {
		if ( successSearchCount != null )
		{
			LinkedList<RuntimeOptimizedComponent> newInputs = new LinkedList<RuntimeOptimizedComponent>();
			
			for(RuntimeOptimizedComponent c : inputs)
			{
				Integer count = 0;
				
				if ( successSearchCount.containsKey(c))
				{
					count = successSearchCount.get(c);
				}
				else
				{
					successSearchCount.put(c, 0);
				}
				
				int i;
				for(i = 0; i < newInputs.size(); i++)
				{
					if ( count > successSearchCount.get(newInputs.get(i)))
					{
						break;
					}
				}
				
				if ( i < newInputs.size() )
				{
					newInputs.add(i,c);
				}
				else
				{
					newInputs.add(c);				
				}
			}
			
			inputs = newInputs;
			successSearchCount = null;
		}
    }

	@Override
    public void reset(boolean disable)
	{
		super.reset(disable);
		knownTrueInput = null;
	}

	@Override
    public void setDirty(boolean from, PolymorphicComponent source)
    {
    	dirtyCount++;
    	
		if (unconditionalDirty)
		{
	    	dirty = true;
	    	
			if ( outputsArray == null )
			{
	    		for(RuntimeOptimizedComponent output : outputs)
	    		{
	    			output.setDirty(cachedValue, this);
	    		}
			}
			else
			{
	    		for(RuntimeOptimizedComponent output : outputsArray)
	    		{
	    			output.setDirty(cachedValue, this);
	    		}
			}
		}
		else
		{
			if ( !dirty && !unconditionalGet )
			{
	    		if ( source == knownTrueInput )
	    		{
	    			refindKnownTrues();
	    		}
	    		
	       	   	if ( null == knownTrueInput )
	    		{
			    	dirty = true;
			    	
					if ( outputsArray == null )
					{
			    		for(RuntimeOptimizedComponent output : outputs)
			    		{
			    			output.setDirty(cachedValue, this);
			    		}
					}
					else
					{
			    		for(RuntimeOptimizedComponent output : outputsArray)
			    		{
			    			output.setDirty(cachedValue, this);
			    		}
					}
	    		}
			}
    	}
    }

	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("ellipse", "grey", "OR");
	}
}