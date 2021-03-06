package coolway99.discordpokebot.battle;

import coolway99.discordpokebot.MoveConstants;
import coolway99.discordpokebot.Player;
import coolway99.discordpokebot.Pokebot;
import coolway99.discordpokebot.moves.MoveSet;
import coolway99.discordpokebot.states.Abilities;
import coolway99.discordpokebot.states.Effects;
import coolway99.discordpokebot.moves.Move;
import sx.blah.discord.handle.obj.IChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("ClassNamePrefixedWithPackageName")
public class Battle{

	public final IChannel channel;
	private final ArrayList<Player> participants;
	/**
	 * Used to make kick those who are inactive from the battle
	 */
	private final ArrayList<Player> threatenTimeout;
	/**
	 * The map of players to attack. It's a tree map, so when it's iterated through the top speed goes first
	 */
	//private final TreeMap<Player, IAttack> attacks;
	private final BattleMap attacks;
	/**
	 * Set the timeout for a turn on the battle
	 */
	private final int turnTime;
	private final BattleTurnTimeout timer;
	private final HashMap<BattleEffects, Integer> battleEffects;

	public Battle(IChannel channel, int turnTime, ArrayList<Player> participants){
		this.channel = channel;
		this.participants = participants;
		this.turnTime = turnTime;
		this.attacks = new BattleMap();
		this.threatenTimeout = new ArrayList<>(2);
		this.battleEffects = new HashMap<>();
		for(Player player : participants){
			player.battle = this;
		}
		StringBuilder builder = new StringBuilder("A battle has started between ");
		Iterator<Player> i = participants.iterator();
		while(i.hasNext()){
			Player player = i.next();
			builder.append(player.mention());
			if(i.hasNext()){
				builder.append(", "); //TODO make this neater
			} else {
				builder.append('!');
			}
		}
		builder.append("\nYou have ");
		builder.append(this.turnTime*2);
		builder.append(" seconds to make your first move!");
		this.sendMessage(builder.toString());
		this.timer = new BattleTurnTimeout(this);
		Pokebot.timer.schedule(this.timer, this.turnTime*2, TimeUnit.SECONDS);
	}

	private void sendMessage(String message){
		Pokebot.sendMessage(this.channel, message);
	}

	public List<Player> getParticipants(){
		return this.participants;
	}

	public void onAttack(IChannel channel, Player attacker, MoveSet moveSet, Player defender){
		synchronized(this.attacks){
			if(this.attacks.containsKey(attacker)){
				this.sendMessage(attacker.mention()+" you've already sent your attack!");
				return;
			}
			this.attacks.put(attacker, new IAttack(attacker, moveSet, defender));
			if(!channel.getID().equals(this.channel.getID())){
				this.sendMessage(attacker.mention()+" sent in their attack from another channel!");
			} else {
				this.sendMessage(attacker.mention()+" submitted their attack");
			}
			Move move = moveSet.getMove();
			attacker.lastMove = moveSet;
			attacker.lastTarget = move.has(Move.Flags.UNTARGETABLE) ? null : defender;
			if(!move.has(Move.Flags.UNTARGETABLE))
				defender.lastAttacker = attacker; //free-for-all may make it weird, but it's intentional
			if(this.attacks.size() == this.participants.size()){
				this.timer.cancel();
				this.onTurn();
			}
		}
	}

	//Called for moves that auto-set themselves
	private void onAutoAttack(Player attacker, MoveSet move, Player defender){
		synchronized(this.attacks){
			this.attacks.put(attacker, new IAttack(attacker, move, defender));
			this.sendMessage(attacker.mention()+" has a multiturn attack!");
		}
	}

	//This is called every time the BattleTurnTimer times out, or if onAttack is completely filled
	public void onTurn(){
		this.timer.cancel();
		synchronized(this.attacks){
			this.sendMessage("Processing attacks");
			for(Player player : this.attacks.keySet()){
				IAttack attack = this.attacks.get(player);
				if(attack.isCanceled()) continue;
				attack.cancel();
				this.threatenTimeout.remove(attack.attacker);
				//We know it's sorted by speed, so only the fastest go first
				if(this.attackLogic(attack)){
					return;
				}
				//Both a recoil check and a failsafe
				if(attack.attacker.HP <= 0){
					this.sendMessage(attack.attacker.mention()+" fainted!");
					if(this.playerFainted(attack.attacker)){
						return;
					}
				}
			}
			//TODO after-turn things
			//Here we do battle-wide effects
			this.battleEffects.replaceAll((effect, integer) -> integer-1);
			this.battleEffects.keySet().removeIf(effect -> {
				if(this.battleEffects.get(effect) <= 0){
					this.sendMessage(effect+" faded away from the field!");
					return true;
				}
				return false;
			});
			//Doing various checks for damage and other things
			for(Player player : this.participants){
				switch(player.getNV()){
					case BURN:{
						//TODO Check for ability heatproof
						player.HP = Math.max(0, player.HP-(player.getMaxHP()/8));
						this.sendMessage(player.mention()+" took damage for it's burn!");
						break;
					}
					case POISON:{
						if(player.hasAbility(Abilities.POISON_HEAL)){
							Move.heal(this.channel, player, player.getMaxHP()/8);
							break;
						}
						player.HP = Math.max(0, player.HP-(player.getMaxHP()/8));
						this.sendMessage(player.mention()+" took damage from poison!");
						break;
					}
					case TOXIC:{
						if(player.hasAbility(Abilities.POISON_HEAL)){
							Move.heal(this.channel, player, player.getMaxHP()/8);
							++player.counter;
							break;
						}
						player.HP = Math.max(0,
								player.HP-(player.getMaxHP()*++player.counter/16));
						this.sendMessage(player.mention()+" took damage from poison!");
						break;
					}
					default:
						break;
				}
				if(player.HP <= 0){
					Move.faintMessage(this.channel, player);
					if(this.playerFainted(player)){return;}
				}
				if(player.has(Effects.Volatile.FLINCH)){
					this.sendMessage(player.mention()+" stopped cringing!");
					player.remove(Effects.Volatile.FLINCH);
				}
			}
			//We check for those who didn't do anything:
			for(Player player : this.threatenTimeout){
				this.onSafeLeaveBattle(player);
				this.sendMessage(player.mention()+" got eliminated for inactivity!");
			}
			if(this.checkDefaultWin()) return;
			this.threatenTimeout.clear();
			this.threatenTimeout.addAll(this.participants);
			this.threatenTimeout.removeAll(this.attacks.keySet());
			for(Player player : this.threatenTimeout){
				this.sendMessage("If "+player.mention()
						+" doesn't attack the next turn, they're out!");
				player.lastTarget = null;
				player.lastMove = null;
				player.lastAttacker = null;
			}
			this.attacks.clear();
			this.sendMessage("Begin next turn, you have "+this.turnTime+" seconds to make your attack");
			Pokebot.timer.schedule(this.timer, this.turnTime, TimeUnit.SECONDS);
			this.participants.forEach(this::onPostTurn);
		}
	}

	private boolean attackLogic(final IAttack attack){
		//Check for flinch status
		if(attack.attacker.has(Effects.Volatile.FLINCH)){
			this.sendMessage("But "+attack.attacker.mention()+" is flinching!");
			attack.attacker.remove(Effects.Volatile.FLINCH);
			return false;
		}
		if(!this.participants.contains(attack.defender)){
			this.sendMessage(attack.attacker.mention()
					+" went to attack, but there was no target!");
			return false;
		}
		if(Move.attack(this.channel, attack)){
			if(attack.defender.lastMove.getMove() == Move.REGISTRY.get("DESTINY_BOND")){
				this.sendMessage(attack.attacker.mention()
						+" was taken down with "+attack.defender.mention());
				attack.attacker.HP = 0;
			}
			if(attack.attacker.lastMove.getMove() == Move.REGISTRY.get("AFTER_YOU")){
				IAttack defenderAttack = this.attacks.get(attack.defender);
				if(defenderAttack != null && !defenderAttack.isCanceled()){
					this.sendMessage(attack.attacker.mention()+" made "+attack.defender.mention()+" go next!");
					//this.attackLogic(defenderAttack.get());
					//defenderAttack.get().cancel();
				} else {
					this.sendMessage("But there wasn't anything to do!"); //TODO better message
				}
			}
			if(this.playerFainted(attack.defender)){
				return true;
			}
			if(attack.defender.hasAbility(Abilities.AFTERMATH)){
				for(Player player : this.participants){
					if(player == attack.defender)
						continue;
					if(player.hasAbility(Abilities.DAMP)){
						this.preventExplosion(player, attack.defender);
						//If the player has <= 0 HP, then the after-attacks check will catch it
						return false;
					}
				}
				//Else we actually do damage
				attack.attacker.HP -= attack.attacker.getMaxHP()/4;
			}
		}
		return false;
	}

	public void set(BattleEffects effect, int duration){
		this.battleEffects.put(effect, duration);
	}

	public boolean has(BattleEffects effect){
		return this.battleEffects.keySet().contains(effect);
	}

	//This is in this class, because only battles prevent explosions
	private void preventExplosion(Player player, Player attacker){
		this.sendMessage("But "+player.mention()+"'s DAMP prevented"
				+attacker.mention()+"'s explosion!");
	}

	/**
	 * Returns true if the battle has ended
	 */
	private boolean playerFainted(Player player){
		this.onSafeLeaveBattle(player);
		if(this.participants.size() == 1){
			Player winner = this.participants.get(0);
			this.participants.clear();
			BattleManager.onBattleWon(this, winner);
			this.timer.cancel();
			return true;
		}
		return false;
	}

	//Should we stop execution?
	private boolean checkDefaultWin(){
		if(this.participants.size() == 1){
			Player player = this.participants.remove(0);
			BattleManager.onExitBattle(player);
			this.participants.clear();
			this.attacks.clear();
			this.threatenTimeout.clear();
			this.sendMessage(player.mention()+" won the battle by default!");
			BattleManager.battles.remove(this);
			return true;
		}
		if(this.participants.size() <= 0){
			this.sendMessage("Nobody won...");
			BattleManager.battles.remove(this);
			return true;
		}
		return false;
	}

	public void onLeaveBattle(Player player){
		BattleManager.onExitBattle(player);
		this.participants.remove(player);
		this.attacks.remove(player);
		this.threatenTimeout.remove(player);
		this.checkDefaultWin();
	}

	private void onSafeLeaveBattle(Player player){
		BattleManager.onExitBattle(player);
		this.participants.remove(player);
	}

	//This is ran after all the battle logic

	/**
	 * Used to run things like post-turn damage.
	 */
	private void onPostTurn(Player player){
		if(player.lastMoveData != MoveConstants.NOTHING) this.onAutoAttack(player, player.lastMove, player.lastTarget);
		for(Effects.VBattle effect : player.getVB()){
			switch(effect){
				case RECHARGING:{
					IAttack fakeAttack = new IAttack(player, null, null);
					fakeAttack.cancel();
					this.sendMessage(player.mention()+" must recharge!");
					this.attacks.put(player, fakeAttack);
					continue;
				}
				default:
					break;
			}
		}
	}

	@SuppressWarnings("ClassNamePrefixedWithPackageName")
	public enum BattleEffects{
		GRAVITY,
		TRICK_ROOM
	}
}