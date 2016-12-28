package coolway99.discordpokebot.jsonUtils;

import coolway99.discordpokebot.battle.Battle;
import coolway99.discordpokebot.moves.rewrite.MoveWrapper;
import sx.blah.discord.handle.obj.IChannel;

/**
 * A class created to keep context between different states. Currently only used for JSON
 */
public class Context{
	public IChannel channel = null;
	public Battle battle = null;
	public MoveWrapper move = null;
}