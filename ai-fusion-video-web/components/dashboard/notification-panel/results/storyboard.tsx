"use client";

import { AlertTriangle, CheckCircle2, Clapperboard, Film } from "lucide-react";
import { resolveMediaUrl } from "@/lib/api/client";
import { storyboardShotTypeNames } from "../constants";

export function StoryboardResult({
  data,
  toolName,
}: {
  data: unknown;
  toolName: string;
}) {
  const obj = data as Record<string, unknown>;
  const isSave = toolName.startsWith("save_") || toolName === "insert_storyboard_item";

  if (isSave) {
    const status = obj.status as string | undefined;
    const message = obj.message as string | undefined;
    const sceneId = (obj.storyboardSceneId ?? obj.sceneId) as number | undefined;
    const sceneNumber = obj.sceneNumber as string | undefined;
    const shotCount = obj.shotCount as number | undefined;
    const episodeId = (obj.storyboardEpisodeId ?? obj.episodeId) as number | undefined;
    const sceneCount = obj.sceneCount as number | undefined;

    return (
      <div className="space-y-1">
        <p className="text-xs text-muted-foreground inline-flex items-center gap-1">
          {status === "error" ? (
            <AlertTriangle className="h-3.5 w-3.5 text-yellow-500 shrink-0" />
          ) : (
            <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" />
          )}
          {message || (status === "error" ? "保存失败" : "保存成功")}
        </p>
        {(sceneNumber || sceneId !== undefined) && (
          <p className="text-[10px] text-muted-foreground/60">
            {sceneNumber ? `场次 ${sceneNumber}` : `场次 ID ${sceneId}`}
            {shotCount !== undefined && ` · ${shotCount} 个镜头`}
          </p>
        )}
        {episodeId !== undefined && (
          <p className="text-[10px] text-muted-foreground/60">
            集 ID: {episodeId}
            {sceneCount !== undefined && ` · ${sceneCount} 个场次`}
          </p>
        )}
      </div>
    );
  }

  const storyboards = obj.storyboards as Array<Record<string, unknown>> | undefined;
  if (storyboards) {
    return (
      <div className="space-y-1">
        <p className="text-xs text-muted-foreground">
          共 <span className="font-medium text-foreground">{storyboards.length}</span> 个分镜脚本
        </p>
        {storyboards.slice(0, 3).map((storyboard, index) => (
          <div
            key={index}
            className="flex items-center gap-2 text-xs text-muted-foreground/90"
          >
            <span className="w-1.5 h-1.5 rounded-full bg-blue-400/60 shrink-0" />
            <span className="truncate">
              {(storyboard.title as string) || `分镜 #${storyboard.id || index + 1}`}
              {storyboard.totalItems !== undefined && ` · ${storyboard.totalItems} 项`}
            </span>
          </div>
        ))}
      </div>
    );
  }

  const sceneName = obj.sceneName as string | undefined;
  const title = obj.title as string | undefined;
  const description = obj.description as string | undefined;
  const totalItems = obj.totalItems as number | undefined;
  const items = obj.items as Array<Record<string, unknown>> | undefined;
  const storyboardId = obj.storyboardId ?? obj.id;
  const sceneId = obj.storyboardSceneId ?? obj.sceneId;
  const shotItems = Array.isArray(items) ? items : [];
  const displayTitle = title || sceneName;

  return (
    <div className="space-y-1.5">
      <div className="space-y-0.5">
        {displayTitle && (
          <p className="text-xs font-medium text-foreground">{displayTitle}</p>
        )}
        <p className="text-[10px] text-muted-foreground/60">
          {storyboardId !== undefined && `ID: ${storyboardId}`}
          {storyboardId === undefined && sceneId !== undefined && `场次 ID: ${sceneId}`}
          {totalItems !== undefined && ` · 共 ${totalItems} 个镜头`}
          {shotItems.length > 0 && ` · 预览 ${Math.min(shotItems.length, 3)} 项`}
        </p>
        {description && (
          <p className="text-[10px] leading-4 text-muted-foreground/70 line-clamp-1">
            {description}
          </p>
        )}
      </div>

      {shotItems.length > 0 ? (
        <div className="space-y-1.5">
          {shotItems.slice(0, 3).map((item, index) => {
            const shotNumber = item.shotNumber ?? item.autoShotNumber ?? index + 1;
            const shotType = item.shotType as string | undefined;
            const cameraMovement = item.cameraMovement as string | undefined;
            const content =
              (item.content as string | undefined) ||
              (item.sceneExpectation as string | undefined) ||
              "（无画面描述）";
            const duration =
              typeof item.duration === "number" ? item.duration : undefined;
            const imageUrl = resolveMediaUrl(
              (item.generatedImageUrl as string | null | undefined) ||
                (item.imageUrl as string | null | undefined)
            );
            const videoUrl = resolveMediaUrl(
              (item.generatedVideoUrl as string | null | undefined) ||
                (item.videoUrl as string | null | undefined)
            );
            const hasImage = !!imageUrl;
            const hasVideo = !!videoUrl;

            return (
              <div
                key={String(item.id ?? index)}
                className="rounded-lg border border-border/25 bg-background/50 px-2 py-1.5"
              >
                <div className="flex gap-2">
                  <div className="relative h-10 w-16 shrink-0 overflow-hidden rounded-md border border-border/20 bg-muted/20">
                    {hasVideo ? (
                      <>
                        <video
                          src={videoUrl || ""}
                          className="h-full w-full object-cover"
                          muted
                          playsInline
                          preload="metadata"
                        />
                        <div className="absolute inset-0 flex items-center justify-center bg-black/15">
                          <Clapperboard className="h-3.5 w-3.5 text-white/90" />
                        </div>
                      </>
                    ) : hasImage ? (
                      <img
                        src={imageUrl || ""}
                        alt={`镜头 ${String(shotNumber)}`}
                        className="h-full w-full object-cover"
                      />
                    ) : (
                      <div className="flex h-full w-full items-center justify-center">
                        <Film className="h-4 w-4 text-muted-foreground/35" />
                      </div>
                    )}
                  </div>

                  <div className="min-w-0 flex-1 space-y-0.5">
                    <div className="flex flex-wrap items-center gap-1">
                      <span className="text-xs font-semibold text-foreground">
                        #{String(shotNumber)}
                      </span>
                      {shotType && (
                        <span className="rounded bg-muted/50 px-1.5 py-0.5 text-[10px] text-muted-foreground">
                          {storyboardShotTypeNames[shotType] || shotType}
                        </span>
                      )}
                      {cameraMovement && (
                        <span className="rounded bg-blue-500/10 px-1.5 py-0.5 text-[10px] text-blue-400">
                          {cameraMovement}
                        </span>
                      )}
                      {duration !== undefined && (
                        <span className="rounded bg-amber-500/10 px-1.5 py-0.5 text-[10px] text-amber-400">
                          {duration}s
                        </span>
                      )}
                    </div>

                    <p className="text-[10px] leading-4 text-muted-foreground/85 line-clamp-2">
                      {content}
                    </p>

                    <div className="flex flex-wrap items-center gap-1">
                      {hasImage && (
                        <span className="text-[10px] text-cyan-400/80">有画面</span>
                      )}
                      {hasVideo && (
                        <span className="text-[10px] text-emerald-400/80">已有视频</span>
                      )}
                      {!hasImage && !hasVideo && (
                        <span className="text-[10px] text-muted-foreground/60">暂无媒体</span>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            );
          })}

          {shotItems.length > 3 && (
            <p className="pl-1 text-[10px] text-muted-foreground/60">
              …还有 {shotItems.length - 3} 个镜头未展开
            </p>
          )}
        </div>
      ) : (
        <p className="text-[11px] text-muted-foreground/70">暂无镜头数据</p>
      )}
    </div>
  );
}