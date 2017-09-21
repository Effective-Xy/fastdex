package fastdex.idea.models;

import com.android.builder.model.SourceProvider;
import com.android.sdklib.AndroidVersion;
import com.android.tools.idea.model.AndroidModel;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * Created by tong on 17/9/21.
 */

public class AndroidModelWrapper implements AndroidModel {
    public static final int MIN_SDK_VERSION = 8;

    private AndroidModel base;

    public AndroidModelWrapper(AndroidModel base) {
        this.base = base;
    }

    @Override
    @Deprecated
    @NotNull
    public SourceProvider getDefaultSourceProvider() {
        return base.getDefaultSourceProvider();
    }

    @Override
    @Deprecated
    @NotNull
    public List<SourceProvider> getActiveSourceProviders() {
        return base.getActiveSourceProviders();
    }

    @Override
    @Deprecated
    @NotNull
    public List<SourceProvider> getTestSourceProviders() {
        return base.getTestSourceProviders();
    }

    @Override
    @Deprecated
    @NotNull
    public List<SourceProvider> getAllSourceProviders() {
        return base.getAllSourceProviders();
    }

    @Override
    @NotNull
    public String getApplicationId() {
        return base.getApplicationId();
    }

    @Override
    @NotNull
    public Set<String> getAllApplicationIds() {
        return base.getAllApplicationIds();
    }

    @Override
    public boolean overridesManifestPackage() {
        return base.overridesManifestPackage();
    }

    @Override
    public Boolean isDebuggable() {
        return base.isDebuggable();
    }

    @Override
    @Nullable
    public AndroidVersion getMinSdkVersion() {
        return new AndroidVersion(MIN_SDK_VERSION,base.getMinSdkVersion().getCodename());
    }

    @Override
    @Nullable
    public AndroidVersion getRuntimeMinSdkVersion() {
        return new AndroidVersion(MIN_SDK_VERSION,base.getRuntimeMinSdkVersion().getCodename());

        //return base.getRuntimeMinSdkVersion();
    }

    @Override
    @Nullable
    public AndroidVersion getTargetSdkVersion() {
        return base.getTargetSdkVersion();
    }

    @Override
    @Nullable
    public Integer getVersionCode() {
        return base.getVersionCode();
    }

    @Override
    @NotNull
    public File getRootDirPath() {
        return base.getRootDirPath();
    }

    @Override
    public boolean isGenerated(@NotNull VirtualFile file) {
        return base.isGenerated(file);
    }

    @Override
    @Deprecated
    @NotNull
    public VirtualFile getRootDir() {
        return base.getRootDir();
    }

    @Override
    public boolean getDataBindingEnabled() {
        return base.getDataBindingEnabled();
    }
}
