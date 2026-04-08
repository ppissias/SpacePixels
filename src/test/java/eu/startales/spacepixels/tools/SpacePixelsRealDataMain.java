package eu.startales.spacepixels.tools;

public final class SpacePixelsRealDataMain {

    private SpacePixelsRealDataMain() {
    }

    public static void main(String[] args) throws Exception {
        new SpacePixelsRealDataIT().runsPreparationApiAndCliAgainstEachDatasetDirectory();
    }
}
