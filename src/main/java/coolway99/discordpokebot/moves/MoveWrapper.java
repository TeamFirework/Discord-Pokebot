package coolway99.discordpokebot.moves;

import coolway99.discordpokebot.Player;
import coolway99.discordpokebot.Context;
import coolway99.discordpokebot.Pokebot;
import coolway99.discordpokebot.jsonUtils.JSONObject;
import coolway99.discordpokebot.states.Types;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumSet;

//A wrapper to Moves from JSON
public class MoveWrapper{

	private final Types type;
	private final int power;
	private final MoveCategory category;
	private final int PP;
	private final int accuracy;
	private final Battle_Priority priority;
	private final int cost;
	private final EnumSet<MoveFlags> flags;
	private final Target target;

	@Nullable
	private final ScriptObjectMirror accuracyFunction;
	@Nullable
	private final ScriptObjectMirror onBeforeFunction;
	private final ScriptObjectMirror onAttackFunction;
	@Nullable
	private final ScriptObjectMirror onSecondaryFunction;

	private final String name;
	private final String displayName;

	@SuppressWarnings("FeatureEnvy")
	public MoveWrapper(JSONObject move){
		this.type = move.getObject("type", Types.NORMAL);
		this.power = move.getInt("power", 0);
		this.category = move.getObject("category", MoveCategory.PHYSICAL);
		this.PP = move.getInt("pp", 0);
		Object accuracy = move.getObject("accuracy");
		if(accuracy == null){
			this.accuracy = 100;
			this.accuracyFunction = null;
		} else {
			if(accuracy instanceof Integer){
				this.accuracy = (int) accuracy;
				this.accuracyFunction = null;
			} else if(accuracy instanceof Boolean){
				this.accuracy = ((boolean) accuracy ) ? 100000 : 0;
				this.accuracyFunction = null;
			} else if(accuracy instanceof ScriptObjectMirror && ((ScriptObjectMirror) accuracy).isFunction()){
				this.accuracy = -1;
				this.accuracyFunction = (ScriptObjectMirror) accuracy;
			} else {
				this.accuracy = 100;
				this.accuracyFunction = null;
			}
		}
		this.priority = Battle_Priority.getPriority(move.getInt("priority", 0));
		this.cost = move.getInt("cost", this.power);

		this.flags = EnumSet.noneOf(MoveFlags.class);
		this.flags.addAll(Arrays.asList(move.getArray("flags", MoveFlags.class,
				new MoveFlags[]{MoveFlags.MAKES_CONTACT, MoveFlags.CAN_BE_MIRRORED})));
		this.target = move.getObject("target", Target.ADJACENT);

		this.onBeforeFunction = move.getFunction("onBeforeAttack");
		this.onAttackFunction = move.getFunction("onAttack");
		this.onSecondaryFunction = move.getFunction("onSecondary");

		this.name = move.getString("id", "default");
		String displayName = move.getString("name");
		if(displayName == null){
			this.displayName = this.name;
		} else {
			this.displayName = displayName;
		}
	}

	public Types getType(){
		return this.type;
	}

	public int getPower(){
		return this.power;
	}

	public MoveCategory getCategory(){
		return this.category;
	}

	public int getPP(){
		return this.PP;
	}

	public int getAccuracy(){
		return this.accuracy;
	}

	public int getAccuracy(Context context, Player attacker, Player defender){
		if(this.accuracyFunction == null) return this.accuracy;
		return (int) this.accuracyFunction.call(this, context, attacker, defender);
	}

	public Battle_Priority getPriority(){
		return this.priority;
	}

	public int getCost(){
		return this.cost;
	}

	public EnumSet<MoveFlags> getFlags(){
		return this.flags;
	}

	public Target getTarget(){
		return this.target;
	}

	/**
	 * OnBeforeAttack, ran before the attack is done.
	 * @param attacker The one running the attack
	 * @param defender The one defending from the attack
	 * @return If to continue or not
	 * Can be null or a function
	 */
	@Contract(pure = true)
	public boolean onBeforeAttack(Context context, Player attacker, Player defender){
		if(this.onBeforeFunction == null) return true;
		Boolean b = (Boolean) this.onBeforeFunction.call(this, context, attacker, defender);
		if(b == null) return true;
		return b;
	}

	/**
	 * The main method for moves. This must always be defined
	 * @param context The context this takes place in
	 * @param attacker The attacker, aka the one performing the attack
	 * @param defender The defender, aka the one receiving the attack
	 */
	public void onAttack(Context context, Player attacker, Player defender){
		if(this.onAttackFunction == null){
			//The default attack function
			MoveUtils.dealDamage(context.channel, attacker, this, defender);
			return;
		}
		this.onAttackFunction.call(this, context, attacker, defender, context.battle);
	}

	/**
	 * OnSecondary, ran after attacking. Only called in battles
	 * @param attacker The one running the attack
	 * @param defender The one defending from the attack
	 * If null or false, then it is skipped, otherwise it must be a function
	 */
	public void onSecondary(Context context, Player attacker, Player defender, int damageDealt){
		if(this.onSecondaryFunction == null || !this.onSecondaryFunction.isFunction()) return;
		this.onSecondaryFunction.call(this, context, attacker, defender, damageDealt);
	}

//	/**
//	 * Called when the enemy attempts to attack them, if defined this overrides
//	 * @param context The context this takes place in
//	 * @param moveID The id of the move being attempted
//	 * @param attacker The attacking pokemon (one running the move)
//	 * @param defender The defending pokemon (us, in this case)
//	 * @param battle If needed, the battle context this takes place in
//	 * @return True if this move can hit us, false if they can't
//	 */
//	public boolean willHit(Context context, String moveID, Player attacker, Player defender, Battle battle){
//		if(this.)
//	}

	public String getID(){
		return this.name;
	}

	public String getName(){
		return this.displayName;
	}

	public boolean has(MoveFlags flag){
		return this.getFlags().contains(flag);
	}
}