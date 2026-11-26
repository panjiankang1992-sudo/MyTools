package com.yuyutian.mytools.news;

/** 保留原预览脚本入口，实际采集复用可独立部署的生产类。 */
public final class PersonalPreviewLauncher {
    private PersonalPreviewLauncher() { }
    /** 委托自用采集入口，不包含测试夹具或虚构新闻。 */
    public static void main(String[] args) throws Exception {
        PersonalCollectorLauncher.main(args);
    }
}
