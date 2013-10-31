package org.ggp.base.util.profile;

import java.util.HashMap;
import java.util.Stack;

public class ProfilerSampleSetSimple extends ProfileSampleSet {
	private HashMap<String,ProfileSample> sampleSet;
	private ThreadLocal<Stack<ProfileSample>> profileStack;
	
	public ProfilerSampleSetSimple()
	{
		sampleSet = new HashMap<String,ProfileSample>();
		profileStack = new ThreadLocal<Stack<ProfileSample>>();
		profileStack.set(new Stack<ProfileSample>());
	}
	
	public void resetStats()
	{
		sampleSet.clear();
	}
	
	public void beginSection(ProfileSection section)
	{
		ProfileSample masterSample = sampleSet.get(section.getName());
		
		if ( masterSample == null )
		{
			masterSample = new ProfileSample(section.getName());
			sampleSet.put(section.getName(), masterSample);
		}
		
		profileStack.get().push(masterSample);
		masterSample.enterInstance();
	}
		
	public void endSection(ProfileSection section)
	{
		Stack<ProfileSample> stack = profileStack.get();
		ProfileSample masterSample = stack.pop();
		
		masterSample.exitInstance(section.getEllapsedTime());
		
		if ( !stack.isEmpty())
		{
			ProfileSample parentSample = stack.peek();

			parentSample.accrueChildTime(section.getEllapsedTime());
		}
	}

	public String toString()
	{
		StringBuilder result = new StringBuilder();
		
		for(ProfileSample sample : sampleSet.values())
		{
			result.append(sample.toString());
			result.append("\n");
		}
		
		return result.toString();
	}
}
