
				<style type="text/css" media="all">
					#cell
					{
						width:  46px;
						height: 46px;
						float:	left;
						border: 2px solid #FFC;
						background-color: #CCCCCC;
					}
				</style>


				<div id="main" style="position:relative; width:150px; height:150px">
					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='1' and argument[2]='1' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='1' and argument[2]='1' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='1' and argument[2]='2' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='1' and argument[2]='2' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='1' and argument[2]='3' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='1' and argument[2]='3' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='2' and argument[2]='1' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='2' and argument[2]='1' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='2' and argument[2]='2' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='2' and argument[2]='2' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='2' and argument[2]='3' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='2' and argument[2]='3' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='3' and argument[2]='1' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='3' and argument[2]='1' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='3' and argument[2]='2' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='3' and argument[2]='2' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					

					<div id="cell">
						<xsl:if test="fact[relation='mark' and argument[1]='3' and argument[2]='3' and argument[3]='x']">
							<p align="center">X</p>
						</xsl:if>	
						<xsl:if test="fact[relation='mark' and argument[1]='3' and argument[2]='3' and argument[3]='o']">
							<p align="center">O</p>
						</xsl:if>	
					</div>					
				</div>
		