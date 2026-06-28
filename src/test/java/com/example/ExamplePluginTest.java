package com.cerberushelper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Run this class's main() (with -ea as a VM option) to launch a real
 * RuneLite client with Cerberus Helper loaded, without publishing it to the
 * Plugin Hub. See README.md for the full setup steps and, importantly, the
 * account-risk disclaimer.
 *
 * NOTE: this mirrors the test class used by RuneLite's official
 * example-plugin template (github.com/runelite/example-plugin). If you get
 * a compile error here, open that repo's ExamplePluginTest.java side by
 * side - the surrounding RuneLite API occasionally shifts between versions
 * and this couldn't be compiled/verified in the environment that wrote it.
 */
public class CerberusHelperPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(CerberusHelperPlugin.class);
		RuneLite.main(args);
	}
}
