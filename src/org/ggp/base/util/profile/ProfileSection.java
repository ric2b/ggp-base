package org.ggp.base.util.profile;

public class ProfileSection {
	private String name;
	private long startTime;
	private long ellapsedTime;
	
	public ProfileSection(String name)
	{
		if ( ProfilerContext.getContext() != null )
		{
			//System.out.println("Enter section: " + name);
			this.name = name;
			ProfilerContext.getContext().beginSection(this);
			startTime = System.nanoTime();
		}
	}
	
	public String getName()
	{
		return name;
	}
	
	public long getEllapsedTime()
	{
		return ellapsedTime;
	}
	
	public void exitScope()
	{
		if ( ProfilerContext.getContext() != null )
		{
			ellapsedTime = System.nanoTime() - startTime;
	
			ProfilerContext.getContext().endSection(this);
			//System.out.println("Exit section: " + name);
		}
	}
}
