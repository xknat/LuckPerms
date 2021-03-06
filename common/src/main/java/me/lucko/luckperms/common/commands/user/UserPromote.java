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

package me.lucko.luckperms.common.commands.user;

import me.lucko.luckperms.api.Node;
import me.lucko.luckperms.api.context.MutableContextSet;
import me.lucko.luckperms.common.actionlog.ExtendedLogEntry;
import me.lucko.luckperms.common.command.CommandResult;
import me.lucko.luckperms.common.command.abstraction.CommandException;
import me.lucko.luckperms.common.command.abstraction.SubCommand;
import me.lucko.luckperms.common.command.access.ArgumentPermissions;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.command.utils.ArgumentParser;
import me.lucko.luckperms.common.command.utils.MessageUtils;
import me.lucko.luckperms.common.command.utils.StorageAssistant;
import me.lucko.luckperms.common.command.utils.TabCompletions;
import me.lucko.luckperms.common.locale.LocaleManager;
import me.lucko.luckperms.common.locale.command.CommandSpec;
import me.lucko.luckperms.common.locale.message.Message;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.Track;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.NodeFactory;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.storage.DataConstraints;
import me.lucko.luckperms.common.utils.Predicates;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UserPromote extends SubCommand<User> {
    public UserPromote(LocaleManager locale) {
        super(CommandSpec.USER_PROMOTE.localize(locale), "promote", CommandPermission.USER_PROMOTE, Predicates.is(0));
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, User user, List<String> args, String label) throws CommandException {
        if (ArgumentPermissions.checkModifyPerms(plugin, sender, getPermission().get(), user)) {
            Message.COMMAND_NO_PERMISSION.send(sender);
            return CommandResult.NO_PERMISSION;
        }

        final String trackName = args.get(0).toLowerCase();
        if (!DataConstraints.TRACK_NAME_TEST.test(trackName)) {
            Message.TRACK_INVALID_ENTRY.send(sender, trackName);
            return CommandResult.INVALID_ARGS;
        }

        Track track = StorageAssistant.loadTrack(trackName, sender, plugin);
        if (track == null) {
            return CommandResult.LOADING_ERROR;
        }

        if (track.getSize() <= 1) {
            Message.TRACK_EMPTY.send(sender, track.getName());
            return CommandResult.STATE_ERROR;
        }

        boolean silent = args.remove("-s");
        MutableContextSet context = ArgumentParser.parseContext(1, args, plugin);

        if (ArgumentPermissions.checkContext(plugin, sender, getPermission().get(), context)) {
            Message.COMMAND_NO_PERMISSION.send(sender);
            return CommandResult.NO_PERMISSION;
        }

        // Load applicable groups
        Set<Node> nodes = user.getEnduringNodes().values().stream()
                .filter(Node::isGroupNode)
                .filter(Node::getValuePrimitive)
                .filter(node -> node.getFullContexts().makeImmutable().equals(context.makeImmutable()))
                .collect(Collectors.toSet());

        nodes.removeIf(g -> !track.containsGroup(g.getGroupName()));

        if (nodes.isEmpty()) {
            String first = track.getGroups().get(0);

            Group nextGroup = plugin.getGroupManager().getIfLoaded(first);
            if (nextGroup == null) {
                Message.USER_PROMOTE_ERROR_MALFORMED.send(sender, first);
                return CommandResult.LOADING_ERROR;
            }

            if (ArgumentPermissions.checkArguments(plugin, sender, getPermission().get(), track.getName(), nextGroup.getName())) {
                Message.COMMAND_NO_PERMISSION.send(sender);
                return CommandResult.NO_PERMISSION;
            }

            user.setPermission(NodeFactory.buildGroupNode(nextGroup.getId()).withExtraContext(context).build());

            Message.USER_TRACK_ADDED_TO_FIRST.send(sender, user.getFriendlyName(), nextGroup.getFriendlyName(), MessageUtils.contextSetToString(context));

            ExtendedLogEntry.build().actor(sender).acted(user)
                    .action("promote", track.getName(), context)
                    .build().submit(plugin, sender);

            StorageAssistant.save(user, sender, plugin);
            plugin.getEventFactory().handleUserPromote(user, track, null, first, sender);
            return CommandResult.SUCCESS;
        }

        if (nodes.size() != 1) {
            Message.TRACK_AMBIGUOUS_CALL.send(sender, user.getFriendlyName());
            return CommandResult.FAILURE;
        }

        final Node oldNode = nodes.stream().findAny().get();
        final String old = oldNode.getGroupName();
        final String next;
        try {
            next = track.getNext(old);
        } catch (IllegalArgumentException e) {
            Message.TRACK_DOES_NOT_CONTAIN.send(sender, track.getName(), old);
            return CommandResult.STATE_ERROR;
        }

        if (next == null) {
            Message.USER_PROMOTE_ERROR_ENDOFTRACK.send(sender, track.getName(), user.getFriendlyName());
            return CommandResult.STATE_ERROR;
        }

        if (!plugin.getStorage().loadGroup(next).join().isPresent()) {
            Message.USER_PROMOTE_ERROR_MALFORMED.send(sender, next);
            return CommandResult.STATE_ERROR;
        }

        Group nextGroup = plugin.getGroupManager().getIfLoaded(next);
        if (nextGroup == null) {
            Message.USER_PROMOTE_ERROR_MALFORMED.send(sender, next);
            return CommandResult.LOADING_ERROR;
        }

        if (ArgumentPermissions.checkArguments(plugin, sender, getPermission().get(), track.getName(), nextGroup.getName())) {
            Message.COMMAND_NO_PERMISSION.send(sender);
            return CommandResult.NO_PERMISSION;
        }

        user.unsetPermission(oldNode);
        user.setPermission(NodeFactory.buildGroupNode(nextGroup.getName()).withExtraContext(context).build());

        if (context.isEmpty() && user.getPrimaryGroup().getStoredValue().orElse(NodeFactory.DEFAULT_GROUP_NAME).equalsIgnoreCase(old)) {
            user.getPrimaryGroup().setStoredValue(nextGroup.getName());
        }

        Message.USER_PROMOTE_SUCCESS.send(sender, user.getFriendlyName(), track.getName(), old, nextGroup.getFriendlyName(), MessageUtils.contextSetToString(context));
        if (!silent) {
            Message.EMPTY.send(sender, MessageUtils.listToArrowSep(track.getGroups(), old, nextGroup.getName(), false));
        }

        ExtendedLogEntry.build().actor(sender).acted(user)
                .action("promote", track.getName(), context)
                .build().submit(plugin, sender);

        StorageAssistant.save(user, sender, plugin);
        plugin.getEventFactory().handleUserPromote(user, track, old, nextGroup.getName(), sender);
        return CommandResult.SUCCESS;
    }

    @Override
    public List<String> tabComplete(LuckPermsPlugin plugin, Sender sender, List<String> args) {
        return TabCompletions.getTrackTabComplete(args, plugin);
    }
}
