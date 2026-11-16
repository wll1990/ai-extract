'use client';

import React from 'react';
import type { Icon } from '@phosphor-icons/react';
import {
  BookOpen,
  Books,
  Brain,
  Buildings,
  CalendarCheck,
  ChartLineUp,
  ChatCircleDots,
  Chats,
  ClipboardText,
  Coins,
  Diamond,
  FilmSlate,
  Flask,
  FolderSimple,
  GearSix,
  GraduationCap,
  Handshake,
  House,
  ListChecks,
  Notebook,
  Path,
  Plug,
  Plugs,
  Signpost,
  SlidersHorizontal,
  SquaresFour,
  UploadSimple,
  User,
  UserCircle,
  UserFocus,
  UserGear,
  Users,
} from '@phosphor-icons/react';

/**
 * 菜单项 → 线性图标（24×24 视框，regular 描边）。
 * 按 label 映射，保证同一路径在「员工上传素材 / 管理员素材管理」下得到不同图标。
 */
const ICONS: Record<string, Icon> = {
  分身广场: UserFocus,
  我的工作台: House,
  经验广场: Books,
  销冠访谈: ChatCircleDots,
  我的空间: User,
  上传素材: UploadSimple,

  销售复盘: ClipboardText,
  线索: Signpost,
  跟进任务: ListChecks,
  教练台账: Notebook,
  我的培训: GraduationCap,
  培训计划: CalendarCheck,

  分身管理: UserGear,
  素材管理: FolderSimple,
  经验颗粒: Diamond,
  分身调优: SlidersHorizontal,
  组织分身: Buildings,
  'IM 分身接入': Plug,

  知识库: BookOpen,
  案例库: FilmSlate,
  专家经验: Brain,
  场景地图: Path,
  数据看板: ChartLineUp,
  评测中心: Flask,

  运营总览: SquaresFour,
  用户管理: Users,
  成员空间: UserCircle,
  对话历史: Chats,
  'Token 统计': Coins,
  'AI 模型配置': GearSix,

  'IM 渠道配置': Plugs,
  合作方管理: Handshake,
  企业合作: Buildings,
  预约演示: CalendarCheck,
};

export function NavIcon({ label, className }: { label: string; className?: string }) {
  const Icon = ICONS[label] ?? SquaresFour;
  return <Icon size={16} className={className} aria-hidden="true" />;
}
