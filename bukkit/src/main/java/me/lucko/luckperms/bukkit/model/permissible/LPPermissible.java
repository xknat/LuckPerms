/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit.model.permissible;

import me.lucko.luckperms.api.Contexts;
import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.bukkit.LPBukkitPlugin;
import me.lucko.luckperms.common.config.ConfigKeys;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.verbose.CheckOrigin;

import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PermissibleBase for LuckPerms.
 *
 * This class overrides all methods defined in PermissibleBase, and provides custom handling
 * from LuckPerms.
 *
 * This means that all permission checks made for a player are handled directly by the plugin.
 * Method behaviour is retained, but alternate implementation is used.
 *
 * "Hot" method calls, (namely #hasPermission) are significantly faster than the base implementation.
 *
 * This class is **thread safe**. This means that when LuckPerms is installed on the server,
 * is is safe to call Player#hasPermission asynchronously.
 */
public class LPPermissible extends PermissibleBase {

    // the LuckPerms user this permissible references.
    private final User user;

    // the player this permissible is injected into.
    private final Player player;

    // the luckperms plugin instance
    private final LPBukkitPlugin plugin;

    // the players previous permissible. (the one they had before this one was injected)
    private PermissibleBase oldPermissible = null;

    // if the permissible is currently active.
    private final AtomicBoolean active = new AtomicBoolean(false);

    // the attachments hooked onto the permissible.
    // this collection is only modified by the attachments themselves
    final Set<LPPermissionAttachment> attachments = ConcurrentHashMap.newKeySet();

    public LPPermissible(Player player, User user, LPBukkitPlugin plugin) {
        super(player);
        this.user = Objects.requireNonNull(user, "user");
        this.player = Objects.requireNonNull(player, "player");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean isPermissionSet(String permission) {
        if (permission == null) {
            throw new NullPointerException("permission");
        }

        Tristate ts = this.user.getCachedData().getPermissionData(calculateContexts()).getPermissionValue(permission, CheckOrigin.PLATFORM_LOOKUP_CHECK);
        return ts != Tristate.UNDEFINED || Permission.DEFAULT_PERMISSION.getValue(isOp());
    }

    @Override
    public boolean isPermissionSet(Permission permission) {
        if (permission == null) {
            throw new NullPointerException("permission");
        }

        Tristate ts = this.user.getCachedData().getPermissionData(calculateContexts()).getPermissionValue(permission.getName(), CheckOrigin.PLATFORM_LOOKUP_CHECK);
        if (ts != Tristate.UNDEFINED) {
            return true;
        }

        if (!this.plugin.getConfiguration().get(ConfigKeys.APPLY_BUKKIT_DEFAULT_PERMISSIONS)) {
            return Permission.DEFAULT_PERMISSION.getValue(isOp());
        } else {
            return permission.getDefault().getValue(isOp());
        }
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null) {
            throw new NullPointerException("permission");
        }

        Tristate ts = this.user.getCachedData().getPermissionData(calculateContexts()).getPermissionValue(permission, CheckOrigin.PLATFORM_PERMISSION_CHECK);
        return ts != Tristate.UNDEFINED ? ts.asBoolean() : Permission.DEFAULT_PERMISSION.getValue(isOp());
    }

    @Override
    public boolean hasPermission(Permission permission) {
        if (permission == null) {
            throw new NullPointerException("permission");
        }

        Tristate ts = this.user.getCachedData().getPermissionData(calculateContexts()).getPermissionValue(permission.getName(), CheckOrigin.PLATFORM_PERMISSION_CHECK);
        if (ts != Tristate.UNDEFINED) {
            return ts.asBoolean();
        }

        if (!this.plugin.getConfiguration().get(ConfigKeys.APPLY_BUKKIT_DEFAULT_PERMISSIONS)) {
            return Permission.DEFAULT_PERMISSION.getValue(isOp());
        } else {
            return permission.getDefault().getValue(isOp());
        }
    }

    /**
     * Adds attachments to this permissible.
     *
     * @param attachments the attachments to add
     */
    public void convertAndAddAttachments(Collection<PermissionAttachment> attachments) {
        for (PermissionAttachment attachment : attachments) {
            new LPPermissionAttachment(this, attachment).hook();
        }
    }

    /**
     * Obtains a {@link Contexts} instance for the player.
     * Values are determined using the plugins ContextManager.
     *
     * @return the calculated contexts for the player.
     */
    private Contexts calculateContexts() {
        return this.plugin.getContextManager().getApplicableContexts(this.player);
    }

    @Override
    public void setOp(boolean value) {
        this.player.setOp(value);
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Set<Map.Entry<String, Boolean>> permissions = this.user.getCachedData().getPermissionData(calculateContexts()).getImmutableBacking().entrySet();
        Set<PermissionAttachmentInfo> ret = new HashSet<>(permissions.size());

        for (Map.Entry<String, Boolean> entry : permissions) {
            ret.add(new PermissionAttachmentInfo(this.player, entry.getKey(), null, entry.getValue()));
        }

        return ret;
    }

    @Override
    public LPPermissionAttachment addAttachment(Plugin plugin) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }

        LPPermissionAttachment ret = new LPPermissionAttachment(this, plugin);
        ret.hook();
        return ret;
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String permission, boolean value) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }
        if (permission == null) {
            throw new NullPointerException("permission");
        }

        PermissionAttachment ret = addAttachment(plugin);
        ret.setPermission(permission, value);
        return ret;
    }

    @Override
    public LPPermissionAttachment addAttachment(Plugin plugin, int ticks) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }

        if (!plugin.isEnabled()) {
            throw new IllegalArgumentException("Plugin " + plugin.getDescription().getFullName() + " is not enabled");
        }

        LPPermissionAttachment ret = addAttachment(plugin);
        if (getPlugin().getBootstrap().getServer().getScheduler().scheduleSyncDelayedTask(plugin, ret::remove, ticks) == -1) {
            ret.remove();
            throw new RuntimeException("Could not add PermissionAttachment to " + this.player + " for plugin " + plugin.getDescription().getFullName() + ": Scheduler returned -1");
        }
        return ret;
    }

    @Override
    public LPPermissionAttachment addAttachment(Plugin plugin, String permission, boolean value, int ticks) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }
        if (permission == null) {
            throw new NullPointerException("permission");
        }

        LPPermissionAttachment ret = addAttachment(plugin, ticks);
        ret.setPermission(permission, value);
        return ret;
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        if (attachment == null) {
            throw new NullPointerException("attachment");
        }

        if (!(attachment instanceof LPPermissionAttachment)) {
            throw new IllegalArgumentException("Given attachment is not a LPPermissionAttachment.");
        }

        LPPermissionAttachment a = ((LPPermissionAttachment) attachment);
        if (a.getPermissible() != this) {
            throw new IllegalArgumentException("Attachment does not belong to this permissible.");
        }

        a.remove();
    }

    @Override
    public void recalculatePermissions() {
        // do nothing
    }

    @Override
    public void clearPermissions() {
        this.attachments.forEach(LPPermissionAttachment::remove);
    }

    public User getUser() {
        return this.user;
    }

    public Player getPlayer() {
        return this.player;
    }

    public LPBukkitPlugin getPlugin() {
        return this.plugin;
    }

    public PermissibleBase getOldPermissible() {
        return this.oldPermissible;
    }

    public AtomicBoolean getActive() {
        return this.active;
    }

    public void setOldPermissible(PermissibleBase oldPermissible) {
        this.oldPermissible = oldPermissible;
    }
}
