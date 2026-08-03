"use client";

import { useState } from "react";
import type { PlanChatMessage } from "@/lib/apiClient";
import { ArticlePlanChat } from "./ArticlePlanChat";
import { ArticlePlanProposals } from "./ArticlePlanProposals";

export function ArticlePlanWorkspace({ projectId }: { projectId: number }) {
  const [history, setHistory] = useState<PlanChatMessage[]>([]);

  return (
    <div className="grid gap-8 lg:grid-cols-2">
      <ArticlePlanChat projectId={projectId} history={history} setHistory={setHistory} />
      <ArticlePlanProposals projectId={projectId} history={history} />
    </div>
  );
}
