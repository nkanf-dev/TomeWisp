package dev.tomewisp.integration.ftb.quests;

public interface FtbQuestsBridge {
    FtbQuestSnapshot.Result snapshot(Object player, boolean clientSide);
}
