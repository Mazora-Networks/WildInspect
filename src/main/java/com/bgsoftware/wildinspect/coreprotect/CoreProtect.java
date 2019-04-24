package com.bgsoftware.wildinspect.coreprotect;

import com.bgsoftware.wildinspect.Locale;
import com.bgsoftware.wildinspect.WildInspectPlugin;
import com.bgsoftware.wildinspect.utils.InspectPlayers;
import net.coreprotect.database.Database;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoreProtect {

    private final WildInspectPlugin plugin;
    private final CoreProtectHook coreProtectHook;

    public CoreProtect(WildInspectPlugin plugin){
        this.plugin = plugin;
        int apiVersion = JavaPlugin.getPlugin(net.coreprotect.CoreProtect.class).getAPI().APIVersion();
        coreProtectHook = apiVersion == 5 ? new CoreProtectHook_API5() : new CoreProtectHook_API6();
    }

    public void performLookup(LookupType type, Player pl, Block bl, int page) {
        if(!plugin.getHooksHandler().hasRegionAccess(pl, bl.getLocation())){
            Locale.NOT_INSIDE_CLAIM.send(pl);
            return;
        }

        if(InspectPlayers.isCooldown(pl)){
            DecimalFormat df = new DecimalFormat();
            df.setMaximumFractionDigits(2);
            Locale.COOLDOWN.send(pl, df.format(InspectPlayers.getTimeLeft(pl) / 1000));
            return;
        }

        if(plugin.getSettings().cooldown != -1)
            InspectPlayers.setCooldown(pl);

        InspectPlayers.setBlock(pl, bl);

        if(plugin.getSettings().historyLimitPage < page){
            Locale.LIMIT_REACH.send(pl);
            return;
        }

        new Thread(() -> {
            try(Connection connection = Database.getConnection(false)){
                if(connection == null){
                    Bukkit.getScheduler().runTaskLater(plugin, () -> performLookup(type, pl, bl, page), 20L);
                    return;
                }

                try(Statement statement = connection.createStatement()){
                    int maxPage = getMaxPage(statement, type, pl, bl);

                    if(maxPage <= page){
                        Locale.LIMIT_REACH.send(pl);
                        return;
                    }

                    String[] resultLines;

                    switch(type){
                        case INTERACTION_LOOKUP:
                            resultLines = coreProtectHook.performInteractLookup(statement, pl, bl, page);
                            break;
                        case BLOCK_LOOKUP:
                            resultLines = coreProtectHook.performBlockLookup(statement, pl, bl, page);
                            break;
                        case CHEST_TRANSACTIONS:
                            resultLines = coreProtectHook.performChestLookup(statement, pl, bl, page);
                            break;
                        default:
                            return;
                    }

                    Matcher matcher;

                    StringBuilder message = new StringBuilder();

                    for(String line : resultLines){
                        if((matcher = Pattern.compile("§3CoreProtect §f- §fNo (.*) found for (.*).").matcher(line)).matches()){
                            switch(matcher.group(1)){
                                case "player interactions":
                                    message.append("\n").append(Locale.NO_BLOCK_INTERACTIONS.getMessage(matcher.group(2)));
                                    break;
                                case "block data":
                                    message.append("\n").append(Locale.NO_BLOCK_DATA.getMessage(matcher.group(2)));
                                    break;
                                case "container transactions":
                                    message.append("\n").append(Locale.NO_CONTAINER_TRANSACTIONS.getMessage(matcher.group(2)));
                                    break;
                            }
                        }
                        else if((matcher = Pattern.compile("§f----- §3(.*) §f----- §7\\(x(.*)/y(.*)/z(.*)\\)").matcher(line)).matches()){
                            message.append("\n").append(Locale.INSPECT_DATA_HEADER.getMessage(matcher.group(2), matcher.group(3), matcher.group(4)));
                        }
                        else if((matcher = Pattern.compile("§7(.*) §f- §3(.*) §f(.*) §3(.*)§f.").matcher(line)).matches()){
                            double days = Double.valueOf(matcher.group(1).split("/")[0]) / 24;
                            if(plugin.getSettings().historyLimitDate >= days) {
                                message.append("\n").append(Locale.INSPECT_DATA_ROW.getMessage(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
                            }
                        }
                        else if((matcher = Pattern.compile("§fPage (.*)/(.*). View older data by typing \"§3/co l <page>§f\".").matcher(line)).matches()){
                            message.append("\n").append(Locale.INSPECT_DATA_FOOTER.getMessage(matcher.group(1),
                                    Math.min(maxPage - 1, plugin.getSettings().historyLimitPage)));
                        }
                    }

                    pl.sendMessage(message.substring(1));
                }
            } catch(SQLException ex){
                ex.printStackTrace();
            }
        }).start();
    }

    private int getMaxPage(Statement statement, LookupType type, Player pl, Block bl){
        String[] resultLines;

        int maxPage = 0;

        while(true) {
            switch(type){
                case INTERACTION_LOOKUP:
                    resultLines = coreProtectHook.performInteractLookup(statement, pl, bl, maxPage);
                    break;
                case BLOCK_LOOKUP:
                    resultLines = coreProtectHook.performBlockLookup(statement, pl, bl, maxPage);
                    break;
                case CHEST_TRANSACTIONS:
                    resultLines = coreProtectHook.performChestLookup(statement, pl, bl, maxPage);
                    break;
                default:
                    return 0;
            }

            int amountOfRows = 0;
            Matcher matcher;

            for (String line : resultLines) {
                if ((matcher = Pattern.compile("§7(.*) §f- §3(.*) §f(.*) §3(.*)§f.").matcher(line)).matches()) {
                    double days = Double.valueOf(matcher.group(1).split("/")[0]) / 24;
                    if(plugin.getSettings().historyLimitDate >= days) {
                        amountOfRows++;
                    }
                }
            }

            if (amountOfRows == 0) {
                return maxPage;
            }

            maxPage++;
        }
    }

}