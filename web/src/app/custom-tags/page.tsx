import { listCustomTags } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { CustomTagManager } from "./CustomTagManager";

export default async function CustomTagsPage() {
  const session = await requireAdminSession();
  const tags = await listCustomTags({ id: Number(session.user.id), role: session.user.role }).catch(() => []);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">カスタムタグ</h1>
      <CustomTagManager tags={tags} />
    </div>
  );
}
