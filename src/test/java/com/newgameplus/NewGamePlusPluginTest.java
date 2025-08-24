package com.newgameplus;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class NewGamePlusPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(NewGamePlusPlugin.class);
		RuneLite.main(args);
	}
}