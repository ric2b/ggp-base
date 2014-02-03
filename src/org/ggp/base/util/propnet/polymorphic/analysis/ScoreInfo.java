package org.ggp.base.util.propnet.polymorphic.analysis;

public class ScoreInfo
{
	double	averageScore = 0;
	double	averageSquaredScore = 0;
	int		numSamples = 0;
	
	public void accrueSample(double value)
	{
		averageScore = (averageScore*numSamples + value)/(numSamples+1);
		averageSquaredScore = (averageSquaredScore*numSamples + value*value)/(numSamples+1);
		numSamples++;
	}
	
	public double getStdDev()
	{
		return Math.sqrt(averageSquaredScore - averageScore*averageScore);
	}
}

