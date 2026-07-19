package dev.openallay.integration.ftb.quests;

public interface FtbQuestsBridge {
    FtbQuestSnapshot.Result snapshot(Object player, boolean clientSide);
}
