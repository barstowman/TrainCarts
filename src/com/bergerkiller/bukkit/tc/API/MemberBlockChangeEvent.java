package com.bergerkiller.bukkit.tc.API;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;

import com.bergerkiller.bukkit.tc.MinecartGroup;
import com.bergerkiller.bukkit.tc.MinecartMember;

public class MemberBlockChangeEvent extends Event {
	private static final long serialVersionUID = 1L;
	
	private MinecartMember member;
	private MemberBlockChangeEvent(MinecartMember member) {
		super("MemberBlockChangeEvent");
		this.member = member;
	}
	
	public MinecartMember getMember() {
		return this.member;
	}
	public MinecartGroup getGroup() {
		return this.member.getGroup();
	}
	
	public static void call(MinecartMember member) {
		Bukkit.getPluginManager().callEvent(new MemberBlockChangeEvent(member));
	}

}
