'use client';

import { ResponsiveBar } from '@nivo/bar';

interface Props {
  data: { scene: string; count: number }[];
}

/** 场景提问 TOP5 柱状图 */
export function SceneBarChart({ data }: Props) {
  if (!data || data.length === 0) return <EmptyPlaceholder label="暂无场景数据" />;

  return (
    <div className="rounded-[12px] bg-surface-2 p-5 shadow-sm">
      <h3 className="mb-3 text-sm font-semibold">📊 场景提问 TOP5</h3>
      <div style={{ height: 220 }}>
        <ResponsiveBar
          data={data}
          keys={['count']}
          indexBy="scene"
          layout="horizontal"
          margin={{ top: 5, right: 20, bottom: 5, left: 60 }}
          padding={0.3}
          colors={['#2563EB']}
          borderRadius={4}
          enableGridY={false}
          enableGridX
          axisBottom={null}
          axisLeft={{ tickSize: 0, tickPadding: 8 }}
          labelSkipWidth={12}
          animate
          tooltip={({ data: d, value }) => (
            <div className="rounded-lg border border-border bg-surface-2 px-3 py-2 text-sm shadow-lg">
              {d.scene}: <strong>{value} 次</strong>
            </div>
          )}
        />
      </div>
    </div>
  );
}

function EmptyPlaceholder({ label }: { label: string }) {
  return (
    <div className="rounded-[12px] bg-surface-2 p-5 shadow-sm flex items-center justify-center h-[220px]">
      <p className="text-sm text-muted-foreground">{label}</p>
    </div>
  );
}
