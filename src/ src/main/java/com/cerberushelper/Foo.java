
package com.cerberushelper;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("cerberushelper")
public interface CerberusHelperConfig extends Config
{
	@ConfigItem(
		keyName = "resetHotkey",
		name = "Reset hotkey",
		description = "Manually reset the attack counter. Useful right after you walk back over the entrance flames to reset a No Ghosts attempt, or if the counter ever looks wrong.",
		position = 1
	)
	default Keybind resetHotkey()
	{
		return new Keybind(KeyEvent.VK_F9, 0);
	}

	@ConfigItem(
		keyName = "noGhostsMode",
		name = "No Ghosts strategy reminders",
		description = "Show a reminder to hold Cerberus above 400 HP until attack #14 begins, and flag it in red if she drops below 400 too early.",
		position = 2
	)
	default boolean noGhostsMode()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showUpcoming",
		name = "Show upcoming attacks",
		description = "Preview the next few attacks below the current one, like rows further down the reference table.",
		position = 3
	)
	default boolean showUpcoming()
	{
		return true;
	}

	@ConfigItem(
		keyName = "upcomingCount",
		name = "Upcoming attacks to show",
		description = "How many future attacks to preview.",
		position = 4
	)
	default int upcomingCount()
	{
		return 4;
	}
}

package com.cerberushelper;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

/**
 * Counts Cerberus's attacks using her fixed 6-tick attack speed, predicts
 * upcoming Combo / Lava / Ghosts specials the same way the static reference
 * table does, and confirms the real-time Summoned Soul order as soon as the
 * three ghosts actually spawn.
 *
 * IMPORTANT - things to verify in-game before trusting this:
 * - CERBERUS_NPC_IDS / SOUL_*_ID below are taken from the OSRS Wiki as of
 *   writing. If a future game update renumbers them, the easiest fix is to
 *   stand near Cerberus or a ghost with RuneLite's Developer Tools panel
 *   open (Object/NPC ID overlay) and update the constants.
 * - The melee/ranged/magic mapping for the three Summoned Soul ids was read
 *   from the wiki's image-gallery tab order, not from an explicit table.
 *   Double check it the first time you fight her with this plugin on: if the
 *   banner's order doesn't match what you see in-game, swap the constants.
 * - This was written and reviewed for logical correctness but never
 *   compiled or run inside a live RuneLite client (no JVM/Gradle/Maven
 *   access in the environment that produced it). Expect to spend a few
 *   minutes fixing build errors and calibrating against a real kill.
 */
@Slf4j
@PluginDescriptor(
	name = "Cerberus Helper",
	description = "Counts Cerberus's attacks and highlights upcoming combos, lava pools and summoned souls, including the live ghost prayer order.",
	tags = {"cerberus", "boss", "slayer", "pvm", "overlay", "prayer"}
)
public class CerberusHelperPlugin extends Plugin
{
	private static final Set<Integer> CERBERUS_NPC_IDS = Set.of(5862, 5863, 5866, 13657);

	private static final int SOUL_MELEE_ID = 5869;
	private static final int SOUL_RANGED_ID = 5867;
	private static final int SOUL_MAGIC_ID = 5868;

	private static final int ATTACK_CYCLE_TICKS = 6;
	private static final int NO_GHOSTS_HOLD_ATTACK = 14;
	private static final int MAX_HP = 600;
	private static final int GHOSTS_HP_THRESHOLD = 400;
	private static final int LAVA_HP_THRESHOLD = 200;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private CerberusHelperConfig config;

	@Inject
	private CerberusTrackerOverlay trackerOverlay;

	@Inject
	private SoulOrderOverlay soulOverlay;

	@Provides
	CerberusHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CerberusHelperConfig.class);
	}

	// ---- fight state ----
	private NPC cerberus;
	private boolean fighting;
	private int attackCount;
	private int ticksSinceAttack;
	private boolean droppedBelow400Early;

	// ---- soul tracking ----
	private final List<NPC> pendingSouls = new ArrayList<>();
	private List<NPC> activeSouls = Collections.emptyList();
	private List<SoulType> soulOrder = Collections.emptyList();
	private boolean[] soulDone = new boolean[0];

	private final HotkeyListener resetHotkeyListener = new HotkeyListener(() -> config.resetHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			log.debug("Cerberus Helper: manual reset via hotkey");
			resetFight();
		}
	};

	@Override
	protected void startUp()
	{
		overlayManager.add(trackerOverlay);
		overlayManager.add(soulOverlay);
		keyManager.registerKeyListener(resetHotkeyListener);
		cerberus = null;
		resetFight();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(trackerOverlay);
		overlayManager.remove(soulOverlay);
		keyManager.unregisterKeyListener(resetHotkeyListener);
		cerberus = null;
	}

	private void resetFight()
	{
		fighting = false;
		attackCount = 0;
		ticksSinceAttack = 0;
		droppedBelow400Early = false;
		pendingSouls.clear();
		activeSouls = Collections.emptyList();
		soulOrder = Collections.emptyList();
		soulDone = new boolean[0];
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		int id = npc.getId();

		if (CERBERUS_NPC_IDS.contains(id))
		{
			cerberus = npc;
			resetFight();
			return;
		}

		if (id == SOUL_MELEE_ID || id == SOUL_RANGED_ID || id == SOUL_MAGIC_ID)
		{
			pendingSouls.add(npc);
			if (pendingSouls.size() >= 3)
			{
				resolveSoulOrder();
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();

		if (npc == cerberus)
		{
			// Either she died, or the room emptied and she reset to idle -
			// either way the attack count and any in-progress souls no
			// longer apply to whatever happens next.
			cerberus = null;
			resetFight();
			return;
		}

		pendingSouls.remove(npc);

		int idx = activeSouls.indexOf(npc);
		if (idx >= 0 && idx < soulDone.length)
		{
			soulDone[idx] = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (cerberus == null)
		{
			return;
		}

		int ratio = cerberus.getHealthRatio();
		int scale = cerberus.getHealthScale();
		if (scale > 0)
		{
			int approxHp = Math.round((ratio / (float) scale) * MAX_HP);

			if (ratio == 0)
			{
				// She's dead; stop the clock even before the despawn event
				// arrives, so the panel doesn't keep ticking through the
				// death animation.
				fighting = false;
				return;
			}

			if (fighting && approxHp < GHOSTS_HP_THRESHOLD && attackCount < NO_GHOSTS_HOLD_ATTACK)
			{
				droppedBelow400Early = true;
			}
		}

		if (!fighting)
		{
			return;
		}

		ticksSinceAttack++;
		if (ticksSinceAttack >= ATTACK_CYCLE_TICKS)
		{
			ticksSinceAttack = 0;
			attackCount++;
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (cerberus == null || event.getActor() != client.getLocalPlayer())
		{
			return;
		}

		// The opening Triple Attack is always attack #1; use the first hit
		// she lands on us to start the cycle so it's synced from the start.
		if (!fighting)
		{
			fighting = true;
			attackCount = 1;
			ticksSinceAttack = 0;
		}
	}

	@Subscribe
	public void onOverheadTextChanged(OverheadTextChanged event)
	{
		if (cerberus == null || event.getActor() != cerberus)
		{
			return;
		}

		String text = event.getOverheadText();
		if (text == null)
		{
			return;
		}

		String normalized = text.trim().toLowerCase();
		if (normalized.startsWith("aaarrr") || normalized.startsWith("grrrr"))
		{
			// Resync to the real mechanic trigger in case the tick count
			// has drifted (e.g. a missed tick during lag).
			ticksSinceAttack = 0;
		}
	}

	private void resolveSoulOrder()
	{
		List<NPC> souls = new ArrayList<>(pendingSouls);
		pendingSouls.clear();
		souls.sort(Comparator.comparingInt(n -> n.getWorldLocation().getX()));

		List<SoulType> order = new ArrayList<>();
		for (NPC soul : souls)
		{
			order.add(soulTypeFor(soul.getId()));
		}

		activeSouls = souls;
		soulOrder = order;
		soulDone = new boolean[souls.size()];
	}

	private SoulType soulTypeFor(int npcId)
	{
		if (npcId == SOUL_MELEE_ID)
		{
			return SoulType.MELEE;
		}
		if (npcId == SOUL_RANGED_ID)
		{
			return SoulType.RANGED;
		}
		return SoulType.MAGIC;
	}

	/**
	 * Predicted category for a given attack number, matching the reference
	 * table's pattern: Combo on 1/11/21/..., Ghosts-slot every 7th, Lava-slot
	 * every 5th, with Combo taking priority over Ghosts over Lava on overlap.
	 * This does not apply the real HP gate or the 10% skip chance - those
	 * are shown as a caveat in the overlay instead of silently baked in here.
	 */
	public AttackType attackTypeFor(int attackNumber)
	{
		if (attackNumber <= 0)
		{
			return AttackType.AUTO;
		}
		if (attackNumber % 10 == 1)
		{
			return AttackType.COMBO;
		}

		boolean ghostsSlot = attackNumber % 7 == 0;
		boolean lavaSlot = attackNumber % 5 == 0;

		if (ghostsSlot)
		{
			return AttackType.GHOSTS;
		}
		if (lavaSlot)
		{
			return AttackType.LAVA;
		}
		return AttackType.AUTO;
	}

	public boolean isGhostsHpGateMet()
	{
		return cerberus != null && approxHp() < GHOSTS_HP_THRESHOLD;
	}

	public boolean isLavaHpGateMet()
	{
		return cerberus != null && approxHp() < LAVA_HP_THRESHOLD;
	}

	private int approxHp()
	{
		if (cerberus == null)
		{
			return MAX_HP;
		}
		int scale = cerberus.getHealthScale();
		if (scale <= 0)
		{
			return MAX_HP;
		}
		return Math.round((cerberus.getHealthRatio() / (float) scale) * MAX_HP);
	}

	// ---- getters used by the overlays ----

	public boolean isFighting()
	{
		return fighting;
	}

	public int getAttackCount()
	{
		return attackCount;
	}

	public int getTicksSinceAttack()
	{
		return ticksSinceAttack;
	}

	public List<SoulType> getSoulOrder()
	{
		return soulOrder;
	}

	public boolean[] getSoulDone()
	{
		return soulDone;
	}

	public boolean isNoGhostsMode()
	{
		return config.noGhostsMode();
	}

	public boolean isShowUpcoming()
	{
		return config.showUpcoming();
	}

	public int getUpcomingCount()
	{
		return config.upcomingCount();
	}

	public boolean hasDroppedBelow400Early()
	{
		return droppedBelow400Early;
	}

	public static int getHoldUntilAttack()
	{
		return NO_GHOSTS_HOLD_ATTACK;
	}

	public static int getAttackCycleTicks()
	{
		return ATTACK_CYCLE_TICKS;
	}
}

package com.cerberushelper;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class CerberusTrackerOverlay extends OverlayPanel
{
	private final CerberusHelperPlugin plugin;

	@Inject
	private CerberusTrackerOverlay(CerberusHelperPlugin plugin)
	{
		super(plugin);
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isFighting())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(205, 0));

		int attackNum = plugin.getAttackCount();
		AttackType type = plugin.attackTypeFor(attackNum);
		int ticksLeft = CerberusHelperPlugin.getAttackCycleTicks() - plugin.getTicksSinceAttack();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Cerberus - Attack " + attackNum)
			.color(Color.WHITE)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Now:")
			.right(annotate(type, plugin))
			.rightColor(type.getColor())
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Next in:")
			.right(ticksLeft + " ticks")
			.rightColor(Color.LIGHT_GRAY)
			.build());

		if (plugin.isShowUpcoming())
		{
			int count = plugin.getUpcomingCount();
			for (int i = 1; i <= count; i++)
			{
				int futureNum = attackNum + i;
				AttackType futureType = plugin.attackTypeFor(futureNum);
				panelComponent.getChildren().add(LineComponent.builder()
					.left("#" + futureNum + ":")
					.right(futureType.getLabel())
					.rightColor(futureType.getColor())
					.build());
			}
		}

		if (plugin.isNoGhostsMode())
		{
			int hold = CerberusHelperPlugin.getHoldUntilAttack();
			if (attackNum < hold)
			{
				boolean early = plugin.hasDroppedBelow400Early();
				panelComponent.getChildren().add(LineComponent.builder()
					.left("No Ghosts:")
					.right("Hold >400hp till #" + hold)
					.rightColor(early ? Color.RED : Color.GREEN)
					.build());

				if (early)
				{
					panelComponent.getChildren().add(LineComponent.builder()
						.left("Warning:")
						.right("Dropped below 400hp!")
						.rightColor(Color.RED)
						.build());
				}
			}
		}

		return super.render(graphics);
	}

	/**
	 * Appends a short caveat for slots that are only "real" if the matching
	 * HP gate has actually been crossed, since the slot pattern alone (used
	 * for prediction) doesn't know Cerberus's current HP.
	 */
	private String annotate(AttackType type, CerberusHelperPlugin plugin)
	{
		switch (type)
		{
			case GHOSTS:
				return type.getLabel() + (plugin.isGhostsHpGateMet() ? "" : " (if <400hp)");
			case LAVA:
				return type.getLabel() + (plugin.isLavaHpGateMet() ? "" : " (if <200hp)");
			default:
				return type.getLabel();
		}
	}
}

package com.cerberushelper;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Big banner that appears the moment the three Summoned Souls actually
 * spawn, showing the real (randomised) prayer order west-to-east, and
 * dimming each soul out once it has attacked.
 */
public class SoulOrderOverlay extends Overlay
{
	private static final String PREFIX = "GHOSTS - Pray: ";
	private static final String ARROW = "  ->  ";

	private final CerberusHelperPlugin plugin;

	@Inject
	private SoulOrderOverlay(CerberusHelperPlugin plugin)
	{
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		List<SoulType> order = plugin.getSoulOrder();
		if (order.isEmpty())
		{
			return null;
		}

		boolean[] done = plugin.getSoulDone();

		Font normalFont = new Font("Arial", Font.BOLD, 16);
		Font nextFont = new Font("Arial", Font.BOLD, 20);
		graphics.setFont(normalFont);
		FontMetrics fm = graphics.getFontMetrics();

		int width = fm.stringWidth(PREFIX);
		for (int i = 0; i < order.size(); i++)
		{
			if (i > 0)
			{
				width += fm.stringWidth(ARROW);
			}
			width += fm.stringWidth(order.get(i).getLabel());
		}
		width += 24;
		int height = 36;

		graphics.setColor(new Color(0, 0, 0, 190));
		graphics.fillRoundRect(0, 0, width, height, 10, 10);

		int x = 12;
		int y = height / 2 + fm.getAscent() / 2 - 2;

		graphics.setFont(normalFont);
		graphics.setColor(Color.WHITE);
		graphics.drawString(PREFIX, x, y);
		x += graphics.getFontMetrics().stringWidth(PREFIX);

		for (int i = 0; i < order.size(); i++)
		{
			if (i > 0)
			{
				graphics.setFont(normalFont);
				graphics.setColor(Color.GRAY);
				graphics.drawString(ARROW, x, y);
				x += graphics.getFontMetrics().stringWidth(ARROW);
			}

			SoulType type = order.get(i);
			boolean isDone = i < done.length && done[i];
			boolean isNext = !isDone && isNext(done, i);

			graphics.setFont(isNext ? nextFont : normalFont);
			graphics.setColor(isDone ? Color.GRAY : type.getColor());
			String label = type.getLabel();
			graphics.drawString(label, x, y);
			x += graphics.getFontMetrics().stringWidth(label);
		}

		return new Dimension(width, height);
	}

	private boolean isNext(boolean[] done, int index)
	{
		for (int i = 0; i < index; i++)
		{
			if (i < done.length && !done[i])
			{
				return false;
			}
		}
		return true;
	}
}

package com.cerberushelper;

import java.awt.Color;

/**
 * The three Summoned Soul combat styles. Per the wiki: red = melee,
 * green = ranged, blue = magic. The order three souls attack in is
 * randomised each time, but always goes west to east.
 */
public enum SoulType
{
	MELEE("MELEE", new Color(220, 60, 60)),
	RANGED("RANGE", new Color(70, 200, 90)),
	MAGIC("MAGE", new Color(80, 140, 240));

	private final String label;
	private final Color color;

	SoulType(String label, Color color)
	{
		this.label = label;
		this.color = color;
	}

	public String getLabel()
	{
		return label;
	}

	public Color getColor()
	{
		return color;
	}
}

package com.cerberushelper;

import java.awt.Color;

/**
 * The category of an attack in Cerberus's cycle, matching the rows in the
 * reference table (Auto / Lava / Ghosts / Combo). This is a *prediction*
 * based purely on the attack number's position in the cycle (1, 5, 7, 10...),
 * exactly like the static reference table - it does not by itself account
 * for the 10% chance Cerberus skips a Lava/Ghosts special, or for the real
 * HP-gating condition. Those are surfaced separately in the overlay text.
 */
public enum AttackType
{
	COMBO("COMBO", new Color(220, 53, 69)),
	GHOSTS("GHOSTS", new Color(80, 140, 240)),
	LAVA("LAVA", new Color(245, 150, 40)),
	AUTO("Auto", new Color(160, 160, 160));

	private final String label;
	private final Color color;

	AttackType(String label, Color color)
	{
		this.label = label;
		this.color = color;
	}

	public String getLabel()
	{
		return label;
	}

	public Color getColor()
	{
		return color;
	}
}
