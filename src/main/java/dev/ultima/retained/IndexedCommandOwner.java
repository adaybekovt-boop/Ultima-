package dev.ultima.retained;

/**
 * CPU-side owner of one indirect command slot. Used so swap-remove can remap
 * {@code commandIndex} without depending on Minecraft renderer types.
 */
public interface IndexedCommandOwner {
    int getCommandIndex();

    void onCommandAttached(int commandIndex, int instanceCount);

    void onCommandMoved(int newCommandIndex);

    void onCommandDetached();
}
