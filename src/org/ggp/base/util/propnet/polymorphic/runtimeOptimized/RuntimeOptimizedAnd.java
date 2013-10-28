package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.polymorphic.PolymorphicAnd;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.runtimeOptimized.RuntimeOptimizedComponent.EncapsulatedCost;


/**
 * The And class is designed to represent logical AND gates.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedAnd extends RuntimeOptimizedComponent implements PolymorphicAnd
{
	private PolymorphicComponent knownFalseInput = null;
	
	public static int numSwaps = 0;
	public static int totalSearchCount = 0;
	public static int numSearches = 0;
	
	private Map<PolymorphicComponent,Integer> successSearchCount = null;
	
	/**
	 * Returns true if and only if every input to the and is true.
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
					if ( !component.getValue() )
					{
						return false;
					}
				}
			}
			else
			{
				for ( PolymorphicComponent component : inputsArray )
				{
					if ( !component.getValue() )
					{
						return false;
					}
				}
			}
			
			return true;
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
					if (!component.getValueAndCost(cost))
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
			
			knownFalseInput = null;
			//	See if we can find a result without further queries first by checking
			//	non-dirty inputs
			if ( inputsArray == null )
			{
				for ( RuntimeOptimizedComponent component : inputs )
				{
					if ( !component.isDirty() )
					{
						if (!component.getValue())
						{
							knownFalseInput = component;
							return false;
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
						if (!component.getValue())
						{
							knownFalseInput = component;
							return false;
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
						
						if ( !component.getValue() )
						{
							numSwaps++;
							knownFalseInput = component;
							return false;
						}
					}
				}
				else
				{
					for ( RuntimeOptimizedComponent component : inputsArray )
					{
						totalSearchCount++;
						
						if ( !component.getValue() )
						{
							numSwaps++;
							knownFalseInput = component;
							return false;
						}
					}
				}
			}

			return true;
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
					if (!component.getValueAndCost(aggregatedCost))
					{
						return false;
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
					if ( !component.getValueAndCost(aggregatedCost) )
					{
						return false;
					}
				}
			}
			
			return true;
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
		knownFalseInput = null;
	}
	
	void reFindKnownFalse()
	{
		knownFalseInput = null;
		
		if ( inputsArray == null )
		{
			for(RuntimeOptimizedComponent input : inputs)
			{
				if ( !input.isDirty() && !input.getValue())
				{
					knownFalseInput = input;
					break;
				}
			}
		}
		else
		{
			for(RuntimeOptimizedComponent input : inputsArray)
			{
				if ( !input.isDirty() && !input.getValue())
				{
					knownFalseInput = input;
					break;
				}
			}
		}
	}

	@Override
    public void setDirty(boolean from, PolymorphicComponent source)
    {
    	dirtyCount++;
    	
		if ( unconditionalDirty )
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
    			if ( source == knownFalseInput )
	    		{
    				reFindKnownFalse();
	    		}
	       	   	
	       	   	if ( null == knownFalseInput )
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
		return toDot("invhouse", "grey", "AND");
	}

}
