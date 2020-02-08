package tw.darkk6.litemod.autofish;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.ISoundEventListener;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.ITextComponent;
import tw.darkk6.litemod.autofish.gui.AutofishConfigPanel;
import tw.darkk6.litemod.autofish.util.Config;
import tw.darkk6.litemod.autofish.util.Lang;
import tw.darkk6.litemod.autofish.util.Log;
import tw.darkk6.litemod.autofish.util.Reference;

import com.mumfrey.liteloader.ChatListener;
import com.mumfrey.liteloader.Configurable;
import com.mumfrey.liteloader.InitCompleteListener;
import com.mumfrey.liteloader.Permissible;
import com.mumfrey.liteloader.Tickable;
import com.mumfrey.liteloader.core.LiteLoader;
import com.mumfrey.liteloader.modconfig.ConfigPanel;
import com.mumfrey.liteloader.permissions.PermissionsManager;
import com.mumfrey.liteloader.permissions.PermissionsManagerClient;

public class LiteModAutoFish implements InitCompleteListener, Tickable, Permissible, ISoundEventListener,ChatListener,Configurable {

	//送出兩次右鍵事件的間隔時間( in ticks)
	private static final int TICK_LEN_BETWEEN_RIGHT_CLICK=60;
	private boolean isFishing=false;//我是否正在釣魚
	private long pullBackms=-1;//上次送出"收回釣竿"右鍵事件的時間
	private boolean iGotFish=false;//根據聲音撥放事件檢查是否抓到魚

    private int beforeSneaking=-1; // required by some servers
    private int sneakingTicksLeft=-1;
    private boolean leftClickPressed = false;

	//對話訊息回報
	private long startFishingMs=-1L;
	//其他
	private Config config;
	
	@Override
	public void onInitCompleted(Minecraft mc, LiteLoader loader) {
		mc.getSoundHandler().addListener(this);
	}

	@Override
	public void init(File configPath) {
		config=Config.get();
		config.init();
	}

	@Override
	public void onChat(ITextComponent chat, String msg){
		if( !config.isPermitted || startFishingMs==-1) return;//沒有在自動釣魚
		if(msg.contains(Reference.TIME_REPORT_STR)){
			Minecraft.getMinecraft().player.sendChatMessage(Lang.get("autofishd.msg.iamautofishing"));
		}
	}
	
	@Override
	public void onTick(Minecraft minecraft, float partialTicks, boolean inGame, boolean clock) {
		if(!config.isPermitted) return;
		//是否抓到魚由 soundPlay 負責
		if (inGame && minecraft.player != null) {
			EntityPlayer player = minecraft.player;
			if (config.enable) {
                // Sneak when necessary
                // beforeSneaking == 0 means we are sneaking
                if (beforeSneaking > -1 || sneakingTicksLeft > -1)
                {
                    //Log.infoChat("Enter beforeSneaking > -1, "+beforeSneaking);
                    int keyCodeSneak = Minecraft.getMinecraft().gameSettings.keyBindSneak.getKeyCode();
                    // for AntiAFK
                    int keyCodeLeftClick = Minecraft.getMinecraft().gameSettings.keyBindAttack.getKeyCode();
                    beforeSneaking--;

                    if (leftClickPressed)
                    {
                        KeyBinding.setKeyBindState(keyCodeLeftClick, false);
                        leftClickPressed = false;
                    }

                    if (sneakingTicksLeft > 0)
                    {
                        //Log.infoChat("waiting sneak, "+sneakingTicksLeft);
                        sneakingTicksLeft--;
                    }
                    if (sneakingTicksLeft == 0)
                    {
                        KeyBinding.setKeyBindState(keyCodeSneak, false);
						if (config.doubleReel)
                        {
                            Log.infoChat("ZA WARUDO!");
                            minecraft.playerController.processRightClick(player, minecraft.world, EnumHand.MAIN_HAND);
                        }
                        //Minecraft.getMinecraft().player.sendQueue.addToSendQueue(new C0BPacketEntityAction(Minecraft.getMinecraft().player, 1));
                        beforeSneaking = -1;
                        sneakingTicksLeft = -1;
                    }
                    else if (beforeSneaking == 0)
                    {
                        //Log.infoChat("Enter beforeSneaking == 0");
                        sneakingTicksLeft = 4;
                        KeyBinding.onTick(keyCodeSneak);
                        KeyBinding.setKeyBindState(keyCodeSneak, true);
                        if (config.antiafk)
                        {
                            KeyBinding.onTick(keyCodeLeftClick);
                            KeyBinding.setKeyBindState(keyCodeLeftClick, true);
                            leftClickPressed = true;
                        }
                    }
                    // else do nothing
                }

				if (config.switchRod && isFishing && hasSentRightClick() && !canSendRightClick(player)) {
					switchFishingRod(player);
				}
				if (canSendRightClick(player)) {
					isFishing = true;
					if (player.fishEntity != null) {
						//紀錄現在的 ms , 提供回報時間用
						if(startFishingMs==-1)
							startFishingMs=System.currentTimeMillis();
						
						if (iGotFish) {
                            Log.infoChat("Reel!");
							minecraft.playerController.processRightClick(player, minecraft.world, EnumHand.MAIN_HAND);
							pullBackms = minecraft.world.getTotalWorldTime();
							iGotFish=false;
                            beforeSneaking = 6;
                            sneakingTicksLeft = -1;
						}
					//==== 這邊底下都是 fishEntity = null ====
					} else if (hasSentRightClick() && minecraft.world.getTotalWorldTime() > pullBackms + TICK_LEN_BETWEEN_RIGHT_CLICK) {
						//沒有揮桿出去，但之前 autofish 有送出過拉桿訊息，且和上次送出訊息時間差距 QUEUE_TICK_LENGTH 個 tick 以上
						//再把竿子丟出去
                        Log.infoChat("Cast!");
						minecraft.playerController.processRightClick(player, minecraft.world, EnumHand.MAIN_HAND);
						pullBackms = -1;
						iGotFish=false;
					} else if(!hasSentRightClick() && minecraft.world.getTotalWorldTime() > pullBackms + TICK_LEN_BETWEEN_RIGHT_CLICK){
						//如果已經超過間隔時間，而且之前沒有透過 mod 送出 right Click , 應該是使用者自行取消了
						startFishingMs=-1;
					}
					//.....
				} else if (isFishing) {
					//手上不是拿釣竿，或者設定不再使用時，重設所有資料
					isFishing = false;
					pullBackms = -1;
					iGotFish=false;
					startFishingMs=-1;
                    beforeSneaking = -1;
                    sneakingTicksLeft = -1;
				}
			}
		}
	}
	
	//這是 Minecraft 內建的 Event , 用來偵測是否抓到魚了 
	@Override
	public void soundPlay(ISound soundIn, SoundEventAccessor accessor) {
		if(!config.isPermitted || soundIn==null || soundIn.getSoundLocation()==null) return;
		String soundName=soundIn.getSoundLocation().getPath();
		if(!config.soundName.equals(soundName)) return;
		//確認一下是自己抓到的，理論上沒問題
		Minecraft mc = Minecraft.getMinecraft();
		if (mc.isGamePaused() || mc.player == null) return;
		EntityPlayer player = mc.player;
		EntityFishHook fishEntity=player.fishEntity;
		if(fishEntity==null) return;
		double dist=0.0D;
		if(config.checkDistance || config.showDistance){
			dist=fishEntity.getDistance(
					soundIn.getXPosF(),
					soundIn.getYPosF(),
					soundIn.getZPosF()
				);
		}
		if(config.checkDistance){
			iGotFish = ( dist <= config.maxDistance );
		}else{
			iGotFish=true;
		}
		if(config.showDistance){
			Log.infoChat(Lang.get("autofishd.msg.logdistance")+dist);
		}
	}
	
	private boolean hasSentRightClick(){
		return pullBackms > 0L;
	}
	
	private void switchFishingRod(EntityPlayer player){
		InventoryPlayer inventory = player.inventory;
		//只搜尋 hotbar , 因為 currentItem 只能指定 0~8
		for (int i = 0; i < 9; i++) {
            // this mainInventory is NonNullList<ItemStack>
			ItemStack item = inventory.mainInventory.get(i);
			if (item != null && item.getItem() == Items.FISHING_ROD && canUseThisRod(item)){
				//這個道具是釣竿，且可以使用 
				inventory.currentItem = i;
				break;
			}
		}
	}
	
	private boolean canSendRightClick(EntityPlayer player){
		if(!isHoldingRod(player)) return false;
		ItemStack item=player.getHeldItemMainhand();
		return config.enable
				&& item.getItemDamage() <= item.getMaxDamage()
				&& canUseThisRod(item);
	}
	
	private boolean canUseThisRod(ItemStack item){
		int durability=item.getMaxDamage() - item.getItemDamage();
		return (!config.preventBreak) || durability> config.breakValue;
	}
	
	private boolean isHoldingRod(EntityPlayer player){
		ItemStack item=player.getHeldItemMainhand();
		if(item==null) return false;
		return item.getItem() == Items.FISHING_ROD;
	}
/********* 權限管理 *********/
	@Override
	public String getPermissibleModName() {return Reference.MOD_ID;}

	@Override
	public float getPermissibleModVersion() {return Reference.PERMIT_VER;}

	@Override
	public void registerPermissions(PermissionsManagerClient manager){ manager.registerModPermission(this,Reference.PERMIT_CAN);}

	@Override
	public void onPermissionsCleared(PermissionsManager manager) {
		config.isPermitted=true;
	}

	@Override
	public void onPermissionsChanged(PermissionsManager manager) {
		config.isPermitted=manager.getPermissions(this).getHasPermission(
				Reference.PERMIT_FULLNODE, 
				false
			);
	}
/********* Gui 設定 ********/
	@Override
	public Class<? extends ConfigPanel> getConfigPanelClass() {
		return AutofishConfigPanel.class;
	}
/********* Mod 基本資訊 *********/
	@Override
	public String getVersion() { return Reference.MOD_VER; }
	@Override
	public void upgradeSettings(String version, File configPath, File oldConfigPath) {}
	@Override
	public String getName() {return Reference.MOD_NAME;}
}
