/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.news;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.news.data.AnnouncementData;
import org.geysermc.floodgate.news.data.BuildSpecificData;
import org.geysermc.floodgate.news.data.CheckAfterData;
import org.geysermc.floodgate.platform.command.CommandUtil;
import org.geysermc.floodgate.util.Constants;
import org.geysermc.floodgate.util.HttpUtils;
import org.geysermc.floodgate.util.HttpUtils.HttpResponse;
import org.geysermc.floodgate.util.Permissions;

public class NewsChecker {
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
    private final CommandUtil commandUtil;
    private final FloodgateLogger logger;

    private final Map<Integer, NewsItem> activeNewsItems = new HashMap<>();
    private final String branch;
    private final int build;

    private boolean firstCheck;

    public NewsChecker(CommandUtil commandUtil, FloodgateLogger logger, String branch, int build) {
        this.commandUtil = commandUtil;
        this.logger = logger;
        this.branch = branch;
        this.build = build;
    }

    public void start() {
        executorService.scheduleWithFixedDelay(this::checkNews, 0, 30, TimeUnit.MINUTES);
    }

    private void schedule(long delayMs) {
        executorService.schedule(this::checkNews, delayMs, TimeUnit.MILLISECONDS);
    }

    private void checkNews() {
        // todo also check news for the downloaded database types

        HttpResponse<JsonArray> response =
                HttpUtils.getSilent(
                        Constants.NEWS_OVERVIEW_URL + Constants.NEWS_PROJECT_NAME,
                        JsonArray.class);

        JsonArray array = response.getResponse();

        // silent mode doesn't throw exceptions, so array will be null on failure
        if (array == null || !response.isCodeOk()) {
            return;
        }

        try {
            for (JsonElement newsItemElement : array) {
                NewsItem newsItem = NewsItem.readItem(newsItemElement.getAsJsonObject());
                if (newsItem != null) {
                    addNews(newsItem);
                }
            }
            firstCheck = false;
        } catch (Exception e) {
            if (logger.isDebug()) {
                logger.error("Error while reading news item", e);
            }
        }
    }

    public void handleNews(Object player, NewsItemAction action) {
        for (NewsItem news : getActiveNews(action)) {
            handleNewsItem(player, news, action);
        }
    }

    private void handleNewsItem(Object player, NewsItem news, NewsItemAction action) {
        switch (action) {
            case ON_SERVER_STARTED:
                if (!firstCheck) {
                    return;
                }
            case BROADCAST_TO_CONSOLE:
                logger.info(news.getMessage());
                break;
            case ON_OPERATOR_JOIN:
                if (player == null) {
                    return;
                }

                if (commandUtil.hasPermission(player, Permissions.NEWS_RECEIVE.get())) {
                    String message = Constants.COLOR_CHAR + "a " + news.getMessage();
                    commandUtil.sendMessage(player, message);
                }
                break;
            case BROADCAST_TO_OPERATORS:
                Collection<Object> onlinePlayers = commandUtil.getOnlinePlayersWithPermission(
                        Permissions.NEWS_RECEIVE.get()
                );

                for (Object onlinePlayer : onlinePlayers) {
                    String message = Constants.COLOR_CHAR + "a " + news.getMessage();
                    commandUtil.sendMessage(onlinePlayer, message);
                }
                break;
        }
    }

    public Collection<NewsItem> getActiveNews() {
        return activeNewsItems.values();
    }

    public Collection<NewsItem> getActiveNews(NewsItemAction action) {
        List<NewsItem> news = new ArrayList<>();
        for (NewsItem item : getActiveNews()) {
            if (item.getActions().contains(action)) {
                news.add(item);
            }
        }
        return news;
    }

    public void addNews(NewsItem item) {
        if (activeNewsItems.containsKey(item.getId())) {
            if (!item.isActive()) {
                activeNewsItems.remove(item.getId());
            }
            return;
        }

        if (!item.isActive()) {
            return;
        }

        switch (item.getType()) {
            case ANNOUNCEMENT:
                if (!item.getDataAs(AnnouncementData.class).isAffected(Constants.NEWS_PROJECT_NAME)) {
                    return;
                }
                break;
            case BUILD_SPECIFIC:
                if (!item.getDataAs(BuildSpecificData.class).isAffected(branch, build)) {
                    return;
                }
                break;
            case CHECK_AFTER:
                long checkAfter = item.getDataAs(CheckAfterData.class).getCheckAfter();
                long delayMs = System.currentTimeMillis() - checkAfter;
                schedule(delayMs > 0 ? delayMs : 0);
                break;
            case CONFIG_SPECIFIC:
                //todo
                break;
        }
        activeNewsItems.put(item.getId(), item);
        activateNews(item);
    }

    private void activateNews(NewsItem item) {
        for (NewsItemAction action : item.getActions()) {
            handleNewsItem(null, item, action);
        }
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
