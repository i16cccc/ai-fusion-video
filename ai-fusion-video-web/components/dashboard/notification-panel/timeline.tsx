"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Download,
  Loader2,
  Wrench,
  XCircle,
} from "lucide-react";
import { StreamMarkdown } from "@/components/dashboard/stream-markdown";
import { StreamThink } from "@/components/dashboard/stream-think";
import { cn } from "@/lib/utils";
import type {
  SubTimelineItem,
  TimelineItem,
} from "@/lib/store/pipeline-store";
import {
  getToolDisplayName,
  isSubAgentTool,
} from "./constants";
import { useSmartScroll } from "./hooks";
import { ToolResultDisplay } from "./results";
import {
  parseTaskContent,
  type TaskMediaLinkInfo,
} from "./utils";

function TaskMediaLinks({ mediaLinks }: { mediaLinks: TaskMediaLinkInfo[] }) {
  if (mediaLinks.length === 0) {
    return null;
  }

  return (
    <div className="space-y-3">
      {mediaLinks.map((mediaLink, index) => (
        <div
          key={`${mediaLink.resolvedUrl}-${index}`}
          className="rounded-xl border border-border/30 bg-muted/20 px-3 py-3"
        >
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="min-w-0 flex-1">
              <p className="text-xs font-medium text-muted-foreground">
                {mediaLink.label}
              </p>
              <a
                href={mediaLink.resolvedUrl}
                target="_blank"
                rel="noreferrer"
                className="mt-1 block break-all text-xs leading-relaxed text-muted-foreground underline decoration-dotted underline-offset-2 hover:text-foreground"
                title={mediaLink.resolvedUrl}
              >
                {mediaLink.resolvedUrl}
              </a>
            </div>
            <a
              href={mediaLink.resolvedUrl}
              target="_blank"
              rel="noreferrer"
              download
              className="inline-flex shrink-0 items-center justify-center gap-1.5 rounded-lg border border-primary/25 bg-primary/10 px-3 py-2 text-xs font-medium text-primary transition-colors hover:bg-primary/15"
            >
              <Download className="h-3.5 w-3.5" />
              下载视频
            </a>
          </div>
        </div>
      ))}
    </div>
  );
}

function ExpandableToolCard({
  toolName,
  toolStatus,
  result,
}: {
  toolName: string;
  toolStatus: "done" | "error";
  result?: string;
}) {
  const [expanded, setExpanded] = useState(true);
  const hasResult = !!result;

  return (
    <div
      className={cn(
        "rounded-xl text-sm border overflow-hidden",
        toolStatus === "done"
          ? "border-green-500/20 bg-green-500/5"
          : "border-destructive/20 bg-destructive/5"
      )}
    >
      <div
        className={cn(
          "flex items-center gap-3 px-4 py-2.5",
          hasResult &&
            "cursor-pointer hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
        )}
        onClick={() => hasResult && setExpanded(!expanded)}
      >
        {toolStatus === "done" ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-green-400 shrink-0" />
        ) : (
          <XCircle className="h-3.5 w-3.5 text-destructive shrink-0" />
        )}
        {isSubAgentTool(toolName) ? (
          <Bot className="h-3.5 w-3.5 text-purple-400 shrink-0" />
        ) : (
          <Wrench className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
        )}
        <span className="font-medium text-xs">{getToolDisplayName(toolName)}</span>
        <span className="flex items-center gap-1 ml-auto">
          {hasResult &&
            (expanded ? (
              <ChevronDown className="h-3.5 w-3.5 text-muted-foreground/50" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/50" />
            ))}
        </span>
      </div>

      {hasResult && (
        <AnimatePresence initial={false}>
          {expanded && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: "auto", opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={{ duration: 0.2 }}
              className="overflow-hidden"
            >
              <div
                className={cn(
                  "border-t px-4 py-3",
                  toolStatus === "error"
                    ? "border-destructive/10"
                    : "border-green-500/10"
                )}
              >
                <ToolResultDisplay toolName={toolName} content={result} />
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      )}
    </div>
  );
}

function CallingToolCard({ toolName }: { toolName: string }) {
  return (
    <div className="rounded-xl text-sm border overflow-hidden border-blue-500/20 bg-blue-500/5">
      <div className="flex items-center gap-3 px-4 py-2.5">
        <Loader2 className="h-3.5 w-3.5 animate-spin text-blue-400 shrink-0" />
        {isSubAgentTool(toolName) ? (
          <Bot className="h-3.5 w-3.5 text-purple-400 shrink-0" />
        ) : (
          <Wrench className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
        )}
        <span className="font-medium text-xs">{getToolDisplayName(toolName)}</span>
        <span className="text-xs text-blue-400/80 ml-auto">调用中…</span>
      </div>
    </div>
  );
}

function SubAgentCard({
  item,
}: {
  item: Extract<TimelineItem, { type: "tool" }>;
}) {
  const [expanded, setExpanded] = useState(true);
  const children = item.children ?? [];
  const isRunning = item.status === "calling";
  const lastContentChild = [...children]
    .reverse()
    .find(
      (
        child
      ): child is Extract<SubTimelineItem, { type: "content" }> =>
        child.type === "content"
    );
  const renderedResult =
    !isRunning && item.result
      ? lastContentChild?.text.trim() === item.result.trim()
        ? null
        : item.result
      : null;
  const hasResult = !!renderedResult;
  const hasContent = children.length > 0 || hasResult;
  const innerScrollRef = useSmartScroll([children], isRunning);

  const toolCount = children.filter((child) => child.type === "tool").length;
  const doneToolCount = children.filter(
    (child) => child.type === "tool" && (child.status === "done" || child.status === "error")
  ).length;
  const activeToolCount = children.filter(
    (child) => child.type === "tool" && child.status === "calling"
  ).length;
  const toolProgressLabel =
    toolCount > 0
      ? isRunning
        ? activeToolCount > 0
          ? `已完成 ${doneToolCount}/${toolCount}`
          : `已执行 ${toolCount} 步`
        : `${toolCount} 步`
      : null;

  return (
    <div
      className={cn(
        "rounded-xl text-sm border overflow-hidden",
        isRunning
          ? "border-purple-500/20 bg-purple-500/5"
          : item.status === "done"
            ? "border-green-500/20 bg-green-500/5"
            : "border-destructive/20 bg-destructive/5"
      )}
    >
      <div
        className="flex items-center gap-2.5 px-4 py-2.5 cursor-pointer hover:bg-black/5 dark:hover:bg-white/5 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        {isRunning ? (
          <Loader2 className="h-3.5 w-3.5 animate-spin text-purple-400 shrink-0" />
        ) : item.status === "done" ? (
          <CheckCircle2 className="h-3.5 w-3.5 text-green-400 shrink-0" />
        ) : (
          <XCircle className="h-3.5 w-3.5 text-destructive shrink-0" />
        )}
        <Bot className="h-3.5 w-3.5 text-purple-400 shrink-0" />
        <span className="font-medium text-xs">{getToolDisplayName(item.name)}</span>
        {toolProgressLabel && (
          <span className="text-[10px] text-muted-foreground/60 ml-1">
            {toolProgressLabel}
          </span>
        )}
        <div className="ml-auto flex items-center gap-2 shrink-0">
          {isRunning && (
            <span className="text-xs text-purple-400/80 text-right">运行中…</span>
          )}
          <span>
            {expanded ? (
              <ChevronDown className="h-3.5 w-3.5 text-muted-foreground/50" />
            ) : (
              <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/50" />
            )}
          </span>
        </div>
      </div>

      <AnimatePresence initial={false}>
        {expanded && hasContent && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden"
          >
            <div
              ref={innerScrollRef}
              className="border-t border-purple-500/10 px-4 py-3 space-y-2 max-h-[400px] overflow-y-auto"
            >
              {children.map((child, index) => {
                if (child.type === "reasoning") {
                  const childIsStreaming = isRunning && index === children.length - 1;
                  const childTitle = child.durationMs
                    ? `思考 (${(child.durationMs / 1000).toFixed(1)}s)`
                    : childIsStreaming
                      ? "思考中"
                      : "思考";
                  return (
                    <StreamThink
                      key={`sub-reasoning-${index}`}
                      title={childTitle}
                      content={child.text}
                      compact
                      maxHeight={120}
                      streaming={childIsStreaming}
                    />
                  );
                }
                if (child.type === "tool") {
                  if (child.status === "calling") {
                    return (
                      <CallingToolCard
                        key={`sub-tool-${child.id}`}
                        toolName={child.name}
                      />
                    );
                  }
                  return (
                    <ExpandableToolCard
                      key={`sub-tool-${child.id}`}
                      toolName={child.name}
                      toolStatus={child.status}
                      result={child.result}
                    />
                  );
                }
                if (child.type === "content") {
                  return (
                    <div
                      key={`sub-content-${index}`}
                      className="text-xs leading-relaxed text-muted-foreground/80"
                    >
                      <StreamMarkdown content={child.text} compact tone="muted" />
                    </div>
                  );
                }
                return null;
              })}

              {hasResult && (
                <div className="text-xs leading-relaxed text-foreground/70">
                  <StreamMarkdown content={renderedResult} compact />
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

export interface MessageTimelineProps {
  reasoningText?: string;
  reasoningDurationMs?: number;
  timeline: TimelineItem[];
  streaming?: boolean;
  error?: string;
}

export function MessageTimeline({
  reasoningText,
  reasoningDurationMs,
  timeline,
  streaming,
  error,
}: MessageTimelineProps) {
  const hasTimelineReasoning = timeline.some((item) => item.type === "reasoning");
  const reasoningTitle = reasoningDurationMs
    ? `思考 (${(reasoningDurationMs / 1000).toFixed(1)}s)`
    : streaming
      ? "思考中"
      : "思考";

  return (
    <>
      {!hasTimelineReasoning && reasoningText && (
        <StreamThink
          title={reasoningTitle}
          content={reasoningText}
          streaming={!!streaming}
        />
      )}

      {timeline.map((item, index) => {
        if (item.type === "reasoning") {
          const itemTitle = item.durationMs
            ? `思考 (${(item.durationMs / 1000).toFixed(1)}s)`
            : streaming && index === timeline.length - 1
              ? "思考中"
              : "思考";
          return (
            <StreamThink
              key={`reasoning-${index}`}
              title={itemTitle}
              content={item.text}
              streaming={streaming && index === timeline.length - 1}
            />
          );
        }

        if (item.type === "tool") {
          if (isSubAgentTool(item.name) || (item.children && item.children.length > 0)) {
            return <SubAgentCard key={`sub-agent-${item.id}`} item={item} />;
          }
          if (item.status === "calling") {
            return <CallingToolCard key={`tool-${item.id}`} toolName={item.name} />;
          }
          return (
            <ExpandableToolCard
              key={`tool-${item.id}`}
              toolName={item.name}
              toolStatus={item.status}
              result={item.result}
            />
          );
        }

        const prevItem = index > 0 ? timeline[index - 1] : null;
        if (
          prevItem?.type === "tool" &&
          (isSubAgentTool(prevItem.name) ||
            (prevItem.children && prevItem.children.length > 0)) &&
          prevItem.result &&
          item.text.trim() === prevItem.result.trim()
        ) {
          return null;
        }

        const { markdownContent, mediaLinks } = parseTaskContent(item.text);
        return (
          <div key={`content-${index}`} className="space-y-3 text-sm leading-relaxed">
            {markdownContent ? <StreamMarkdown content={markdownContent} /> : null}
            <TaskMediaLinks mediaLinks={mediaLinks} />
          </div>
        );
      })}

      {error && (
        <div className="flex items-start gap-2 rounded-lg border border-destructive/20 bg-destructive/5 px-3 py-2 text-sm text-destructive">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="leading-relaxed">{error}</span>
        </div>
      )}
    </>
  );
}