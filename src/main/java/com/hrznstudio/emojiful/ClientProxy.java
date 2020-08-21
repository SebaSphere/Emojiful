package com.hrznstudio.emojiful;

import com.esotericsoftware.yamlbeans.YamlException;
import com.esotericsoftware.yamlbeans.YamlReader;
import com.google.gson.JsonElement;
import com.hrznstudio.emojiful.api.Emoji;
import com.hrznstudio.emojiful.api.EmojiFromGithub;
import com.hrznstudio.emojiful.api.EmojiFromTwitmoji;
import com.hrznstudio.emojiful.gui.*;
import com.hrznstudio.emojiful.render.EmojiFontRenderer;
import com.hrznstudio.emojiful.util.ProfanityFilter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.RenderComponentsUtil;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.text.TextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ClientProxy {

    public static ClientProxy PROXY = new ClientProxy();
    public static FontRenderer oldFontRenderer;
    public static List<String> ALL_EMOJIS = new ArrayList<>();
    public static HashMap<String, List<Emoji[]>> SORTED_EMOJIS_FOR_SELECTION = new LinkedHashMap<>();
    public static final List<String> CATEGORIES = new ArrayList<>();
    public static int lineAmount;

    public static void registerClient(){
        FMLJavaModLoadingContext.get().getModEventBus().addListener(PROXY::setup);
        MinecraftForge.EVENT_BUS.register(PROXY);
    }

    public static EmojiSuggestionHelper emojiSuggestionHelper;
    public static EmojiSelectionGui emojiSelectionGui;

    @OnlyIn(Dist.CLIENT)
    public void setup(final FMLClientSetupEvent event) {
        preInitEmojis();
        initEmojis();
        ALL_EMOJIS = Emojiful.EMOJI_LIST.stream().map(emoji -> emoji.strings).flatMap(Collection::stream).collect(Collectors.toList());
        for (String category : CATEGORIES) {
            ++lineAmount;
            Emoji[] array = new Emoji[9];
            int i = 0;
            for (Emoji emoji : Emojiful.EMOJI_MAP.getOrDefault(category, new ArrayList<>())) {
                array[i] = emoji;
                ++i;
                if (i >= array.length){
                    SORTED_EMOJIS_FOR_SELECTION.computeIfAbsent(category, s -> new ArrayList<>()).add(array);
                    array = new Emoji[9];
                    i = 0;
                    ++lineAmount;
                }
            }
            if (i > 0){
                SORTED_EMOJIS_FOR_SELECTION.computeIfAbsent(category, s -> new ArrayList<>()).add(array);
                ++lineAmount;
            }
        }
        Emojiful.LOGGER.info("Loaded " + Emojiful.EMOJI_LIST.size() + " emojis");
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void guiInit(GuiScreenEvent.InitGuiEvent.Post event){
        if (event.getGui() instanceof ChatScreen){
            emojiSuggestionHelper = new EmojiSuggestionHelper((ChatScreen) event.getGui());
            emojiSelectionGui = new EmojiSelectionGui((ChatScreen) event.getGui());
        }
    }

    @SubscribeEvent
    public void render(GuiScreenEvent.DrawScreenEvent.Post event){
        if (emojiSuggestionHelper != null) emojiSuggestionHelper.render(event.getMatrixStack());
        if (emojiSelectionGui != null){
            emojiSelectionGui.mouseMoved(event.getMouseX(), event.getMouseY());
            emojiSelectionGui.render(event.getMatrixStack());
        }
    }

    @SubscribeEvent
    public void onKeyPressed(GuiScreenEvent.KeyboardKeyPressedEvent event){
        if (emojiSuggestionHelper != null && emojiSuggestionHelper.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) event.setCanceled(true);
        if (emojiSelectionGui != null && emojiSelectionGui.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onClick(GuiScreenEvent.MouseClickedEvent.Pre event){
        if (emojiSelectionGui != null) emojiSelectionGui.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
    }

    @SubscribeEvent
    public void onScroll(GuiScreenEvent.MouseScrollEvent.Pre event){
        if (emojiSelectionGui != null) emojiSelectionGui.mouseScrolled(event.getMouseX(), event.getMouseY(), event.getScrollDelta());
    }

    @SubscribeEvent
    public void onClose(TickEvent.ClientTickEvent event){
        if (emojiSelectionGui != null && Minecraft.getInstance().currentScreen != emojiSelectionGui.getChatScreen()) emojiSelectionGui = null;
    }

    @SubscribeEvent
    public void onCharTyped(GuiScreenEvent.KeyboardCharTypedEvent event){
        if (emojiSelectionGui != null && emojiSelectionGui.charTyped(event.getCodePoint(), event.getModifiers())) event.setCanceled(true);
    }

    @SubscribeEvent
    public void onChatRecieved(ClientChatReceivedEvent event){
        if (EmojifulConfig.getInstance().profanityFilter.get() && event.getMessage() instanceof TranslationTextComponent && ((TranslationTextComponent) event.getMessage()).getKey().equals("chat.type.text")){
            TextComponent component = (TextComponent) ((TranslationTextComponent) event.getMessage()).getFormatArgs()[1];
            TranslationTextComponent translationTextComponent = new TranslationTextComponent("chat.type.text", ((TranslationTextComponent) event.getMessage()).getFormatArgs()[0], net.minecraftforge.common.ForgeHooks.newChatWithLinks(ProfanityFilter.filterText(component.getString())));
            event.setMessage(translationTextComponent);
        }
    }

    private void preInitEmojis() {
        CATEGORIES.addAll(Arrays.asList("Smileys & Emotion", "Animals & Nature", "Food & Drink", "Activities", "Travel & Places", "Objects", "Symbols", "Flags"));
        loadCustomEmojis();
        //loadGithubEmojis();
        loadTwitmojis();
        if (EmojifulConfig.getInstance().profanityFilter.get()) ProfanityFilter.loadConfigs();
    }

    private void loadCustomEmojis(){
        try {
            YamlReader reader = new YamlReader(new StringReader(Emojiful.readStringFromURL("https://raw.githubusercontent.com/InnovativeOnlineIndustries/emojiful-assets/master/Categories.yml")));
            ArrayList<String> categories = (ArrayList<String>) reader.read();
            for (String category : categories) {
                CATEGORIES.add(0, category.replace(".yml", ""));
                List<Emoji> emojis = Emojiful.readCategory(category);
                Emojiful.EMOJI_LIST.addAll(emojis);
                Emojiful.EMOJI_MAP.put(category.replace(".yml", ""), emojis);
            }
        } catch (YamlException e) {
            Emojiful.error = true;
        }
    }

    private void loadApiEmojis(){
        for (JsonElement categories : Emojiful.readJsonFromUrl("https://www.emojidex.com/api/v1/categories").getAsJsonObject().getAsJsonArray("categories")) {
            Emojiful.EMOJI_MAP.put(categories.getAsJsonObject().get("code").getAsString(), new ArrayList<>());
        }
    }

    public void loadGithubEmojis(){
        Emojiful.EMOJI_MAP.put("Github", new ArrayList<>());
        for (Map.Entry<String, JsonElement> entry : Emojiful.readJsonFromUrl("https://api.github.com/emojis").getAsJsonObject().entrySet()) {
            EmojiFromGithub emoji = new EmojiFromGithub();
            emoji.name = entry.getKey();
            emoji.strings = new ArrayList<>();
            emoji.strings.add(":" + entry.getKey() + ":");
            emoji.location = entry.getKey();
            emoji.url = entry.getValue().getAsString();
            Emojiful.EMOJI_MAP.get("Github").add(emoji);
            Emojiful.EMOJI_LIST.add(emoji);
        }
    }

    public void loadTwitmojis(){
        for (JsonElement element : Emojiful.readJsonFromUrl("https://raw.githubusercontent.com/iamcal/emoji-data/master/emoji.json").getAsJsonArray()){
            if (element.getAsJsonObject().get("has_img_twitter").getAsBoolean()){
                EmojiFromTwitmoji emoji = new EmojiFromTwitmoji();
                emoji.name = element.getAsJsonObject().get("short_name").getAsString();
                emoji.location = element.getAsJsonObject().get("image").getAsString();
                emoji.sort =  element.getAsJsonObject().get("sort_order").getAsInt();
                List<String> strings = new ArrayList<>();
                element.getAsJsonObject().get("short_names").getAsJsonArray().forEach(jsonElement -> strings.add(":" + jsonElement.getAsString() + ":"));
                if (!element.getAsJsonObject().get("text").isJsonNull()){
                    strings.add(element.getAsJsonObject().get("text").getAsString());
                }
                if (strings.contains(":face_with_symbols_on_mouth:")){
                    strings.add(":swear:");
                }
                if (!element.getAsJsonObject().get("texts").isJsonNull()){
                    element.getAsJsonObject().get("texts").getAsJsonArray().forEach(jsonElement -> strings.add(jsonElement.getAsString()));
                }
                emoji.strings = strings;
                Emojiful.EMOJI_MAP.computeIfAbsent(element.getAsJsonObject().get("category").getAsString(), s -> new ArrayList<>()).add(emoji);
                Emojiful.EMOJI_LIST.add(emoji);
            }
        }
        Emojiful.EMOJI_MAP.values().forEach(emojis -> emojis.sort(Comparator.comparingInt(o -> o.sort)));
    }

    @OnlyIn(Dist.CLIENT)
    private void initEmojis() {
        if (!Emojiful.error) {
            oldFontRenderer = Minecraft.getInstance().fontRenderer;
            Minecraft.getInstance().fontRenderer = new EmojiFontRenderer(Minecraft.getInstance().fontRenderer);
            Minecraft.getInstance().getRenderManager().textRenderer = Minecraft.getInstance().fontRenderer;
        }
    }

}
