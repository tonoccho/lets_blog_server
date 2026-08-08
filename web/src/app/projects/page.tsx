import Link from "next/link";
import { listProjects } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { formatDateTime } from "@/lib/formatDate";
import { ProjectForm } from "./ProjectForm";
import { ProjectsTable } from "./ProjectsTable";

export default async function ProjectsPage() {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const [projects, timezone] = await Promise.all([listProjects(actor).catch(() => []), getViewerTimeZone()]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">プロジェクト</h1>

      <ProjectsTable projects={projects} timezone={timezone} />

      <div id="project-form">
        <ProjectForm />
      </div>
    </div>
  );
}

