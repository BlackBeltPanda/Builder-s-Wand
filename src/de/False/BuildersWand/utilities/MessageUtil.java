package de.False.BuildersWand.utilities;

import de.False.BuildersWand.ConfigurationFiles.Locales;
import org.bukkit.entity.Player;

import java.util.HashMap;

public class MessageUtil
{
    private static String defaultLocale = "en_us";

    public static void sendMessage(Player player, String messagePath)
    {
        player.sendMessage(getMessage(messagePath, player));
    }

    private static String getMessage(String messagePath, Player player)
    {
        String locale = getPlayerLocale(player);
        HashMap<String, String> messages = getMessagesForLocale(locale);

        if(messages.containsKey(messagePath))
        {

            return messages.get(messagePath);
        }

        return messagePath;
    }

    private static HashMap<String, String> getMessagesForLocale(String locale)
    {
        System.out.println(locale);
        if(Locales.messages.containsKey(locale))
        {
            return Locales.messages.get(locale);
        }

        return Locales.messages.get(defaultLocale);
    }

    private static String getPlayerLocale(Player player)
    {
        return player.getLocale();
    }

    public static String getText(String messagePath, Player player)
    {
        return getMessage(messagePath, player);
    }
}
