import React, { useState, useEffect, useRef } from 'react';
import QRCode from 'qrcode';
import { 
  QrCode, 
  Copy, 
  Check, 
  Download, 
  Upload, 
  Smartphone, 
  FileJson, 
  X, 
  Sparkles,
  Info,
  Clock,
  Layers,
  ArrowRight
} from 'lucide-react';
import { WorkoutPlan } from '../types';
import { useI18n } from '../i18n/context';
import { 
  encodePlanToSyncString, 
  encodePlanToItimerJSON, 
  encodeAllPresetsToItimerJSON, 
  decodeSyncPayload 
} from '../utils/syncHelper';

interface SyncModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentPlan: WorkoutPlan;
  allPresets: WorkoutPlan[];
  onImportPlan: (plan: WorkoutPlan) => void;
  onImportAllPresets: (presets: WorkoutPlan[]) => void;
}

type SyncTab = 'qr' | 'code' | 'file' | 'import';

export const SyncModal: React.FC<SyncModalProps> = ({
  isOpen,
  onClose,
  currentPlan,
  allPresets,
  onImportPlan,
  onImportAllPresets,
}) => {
  const { t } = useI18n();
  const [activeTab, setActiveTab] = useState<SyncTab>('qr');
  const [syncTarget, setSyncTarget] = useState<'current' | 'all'>('current');
  const [copiedCode, setCopiedCode] = useState(false);
  const [pasteInput, setPasteInput] = useState('');
  const [importStatus, setImportStatus] = useState<{ success?: string; error?: string } | null>(null);

  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // Active payload calculation
  const targetPlan = currentPlan;
  const syncString = encodePlanToSyncString(targetPlan);
  const fullJson = syncTarget === 'current' 
    ? encodePlanToItimerJSON(targetPlan) 
    : encodeAllPresetsToItimerJSON(allPresets);

  // Generate QR Code onto canvas whenever target changes or modal opens
  useEffect(() => {
    if (!isOpen || activeTab !== 'qr') return;

    const canvas = canvasRef.current;
    if (!canvas) return;

    // Use compact sync string for fast scan reliability
    QRCode.toCanvas(
      canvas,
      syncString,
      {
        width: 260,
        margin: 2,
        color: {
          dark: '#0f172a', // Slate 900
          light: '#f8fafc', // Slate 50
        },
        errorCorrectionLevel: 'M',
      },
      (error) => {
        if (error) console.error('QR code generation error:', error);
      }
    );
  }, [isOpen, activeTab, syncString]);

  if (!isOpen) return null;

  const handleCopyCode = () => {
    navigator.clipboard.writeText(syncString);
    setCopiedCode(true);
    setTimeout(() => setCopiedCode(false), 2500);
  };

  const handleDownloadFile = () => {
    const filename = syncTarget === 'current'
      ? `${targetPlan.name.toLowerCase().replace(/[^a-z0-9а-яё]/gi, '_')}.itimer.json`
      : 'workout_presets_bundle.itimer.json';
    
    const blob = new Blob([fullJson], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  const handleDownloadQR = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const url = canvas.toDataURL('image/png');
    const a = document.createElement('a');
    a.href = url;
    a.download = `${targetPlan.name}_qr.png`;
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  const handleProcessImport = (text: string) => {
    setImportStatus(null);
    const res = decodeSyncPayload(text);
    if (res.error) {
      setImportStatus({ error: t('sync_import_error') + ` (${res.error})` });
      return;
    }
    if (res.plan) {
      onImportPlan(res.plan);
      setImportStatus({ success: `${t('sync_import_success')}: "${res.plan.name}"` });
      setPasteInput('');
      setTimeout(() => {
        onClose();
      }, 1500);
    } else if (res.presets) {
      onImportAllPresets(res.presets);
      setImportStatus({ success: `${t('sync_import_success')} (${res.presets.length} пресетов)` });
      setPasteInput('');
      setTimeout(() => {
        onClose();
      }, 1500);
    }
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      const text = event.target?.result as string;
      if (text) handleProcessImport(text);
    };
    reader.readAsText(file);
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-3 sm:p-4">
      <div className="bg-zinc-950 border border-zinc-800 rounded-3xl w-full max-w-2xl max-h-[92vh] flex flex-col overflow-hidden shadow-2xl animate-fadeIn">
        {/* Modal Header */}
        <div className="p-5 border-b border-zinc-800 flex items-center justify-between bg-zinc-900/60">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-2xl bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-center text-emerald-400">
              <Smartphone className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-bold text-white text-base sm:text-lg">
                  {t('sync_modal_title')}
                </h3>
                <span className="text-[11px] px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-400 font-mono font-semibold">
                  NDK ↔ Web
                </span>
              </div>
              <p className="text-xs text-zinc-400 mt-0.5">
                {t('sync_modal_subtitle')}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-8 h-8 rounded-xl bg-zinc-800/80 hover:bg-zinc-700 text-zinc-400 hover:text-white flex items-center justify-center transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Plan Overview Pill Banner */}
        <div className="bg-zinc-900/30 border-b border-zinc-800/80 px-5 py-3 flex flex-wrap items-center justify-between gap-3 text-xs">
          <div className="flex items-center gap-2">
            <span className="text-zinc-400">Профиль:</span>
            <strong className="text-emerald-400 font-bold text-sm">{targetPlan.name}</strong>
          </div>
          <div className="flex items-center gap-3 text-zinc-300">
            <span className="flex items-center gap-1">
              <Layers className="w-3.5 h-3.5 text-zinc-500" />
              <span>{targetPlan.sets.length} {t('journal_table_sets')}</span>
            </span>
            <span>•</span>
            <span className="flex items-center gap-1">
              <Clock className="w-3.5 h-3.5 text-zinc-500" />
              <span>{targetPlan.cycles} {t('journal_table_cycles')}</span>
            </span>
            {targetPlan.prepSeconds > 0 && (
              <>
                <span>•</span>
                <span className="text-amber-400 font-mono">{targetPlan.prepSeconds}s prep</span>
              </>
            )}
          </div>
        </div>

        {/* Tab Navigation */}
        <div className="flex items-center gap-1 p-2 bg-zinc-900/40 border-b border-zinc-800 overflow-x-auto text-xs">
          <button
            onClick={() => setActiveTab('qr')}
            className={`flex items-center gap-1.5 px-3.5 py-2 rounded-xl font-semibold transition-all shrink-0 ${
              activeTab === 'qr'
                ? 'bg-emerald-500 text-zinc-950 shadow-sm'
                : 'text-zinc-400 hover:text-white hover:bg-zinc-800/60'
            }`}
          >
            <QrCode className="w-4 h-4" />
            <span>{t('sync_tab_qr')}</span>
          </button>

          <button
            onClick={() => setActiveTab('code')}
            className={`flex items-center gap-1.5 px-3.5 py-2 rounded-xl font-semibold transition-all shrink-0 ${
              activeTab === 'code'
                ? 'bg-emerald-500 text-zinc-950 shadow-sm'
                : 'text-zinc-400 hover:text-white hover:bg-zinc-800/60'
            }`}
          >
            <Copy className="w-4 h-4" />
            <span>{t('sync_tab_code')}</span>
          </button>

          <button
            onClick={() => setActiveTab('file')}
            className={`flex items-center gap-1.5 px-3.5 py-2 rounded-xl font-semibold transition-all shrink-0 ${
              activeTab === 'file'
                ? 'bg-emerald-500 text-zinc-950 shadow-sm'
                : 'text-zinc-400 hover:text-white hover:bg-zinc-800/60'
            }`}
          >
            <FileJson className="w-4 h-4" />
            <span>{t('sync_tab_file')}</span>
          </button>

          <button
            onClick={() => setActiveTab('import')}
            className={`flex items-center gap-1.5 px-3.5 py-2 rounded-xl font-semibold transition-all shrink-0 ${
              activeTab === 'import'
                ? 'bg-emerald-500 text-zinc-950 shadow-sm'
                : 'text-zinc-400 hover:text-white hover:bg-zinc-800/60'
            }`}
          >
            <Upload className="w-4 h-4" />
            <span>{t('sync_tab_import')}</span>
          </button>
        </div>

        {/* Tab Content */}
        <div className="p-5 overflow-y-auto flex-1 flex flex-col items-center justify-start gap-4">
          {/* TAB 1: QR CODE */}
          {activeTab === 'qr' && (
            <div className="flex flex-col items-center text-center max-w-md w-full">
              <div className="bg-white p-3 rounded-2xl shadow-xl border-4 border-slate-800 mb-3">
                <canvas ref={canvasRef} className="rounded-lg" />
              </div>
              <p className="text-xs text-zinc-300 font-medium leading-relaxed">
                {t('sync_qr_hint')}
              </p>
              <div className="flex items-center gap-2 mt-4">
                <button
                  onClick={handleDownloadQR}
                  className="flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-zinc-900 border border-zinc-700 hover:bg-zinc-800 text-xs font-semibold text-zinc-300 hover:text-white transition-colors"
                >
                  <Download className="w-3.5 h-3.5" />
                  <span>Сохранить картинку QR</span>
                </button>
                <button
                  onClick={handleCopyCode}
                  className="flex items-center gap-1.5 px-3.5 py-2 rounded-xl bg-emerald-500/20 border border-emerald-500/40 hover:bg-emerald-500/30 text-xs font-bold text-emerald-400 transition-colors"
                >
                  {copiedCode ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{copiedCode ? t('sync_copied_code') : t('sync_copy_code')}</span>
                </button>
              </div>
            </div>
          )}

          {/* TAB 2: TEXT SYNC CODE */}
          {activeTab === 'code' && (
            <div className="w-full flex flex-col gap-3">
              <div className="bg-zinc-900/80 border border-zinc-800 rounded-2xl p-4">
                <div className="flex items-center justify-between mb-2">
                  <span className="text-xs font-bold text-zinc-300">
                    {t('sync_code_hint')}
                  </span>
                  <span className="text-[10px] text-zinc-500 font-mono">
                    {syncString.length} chars (Base64)
                  </span>
                </div>
                <div className="bg-zinc-950 rounded-xl p-3 font-mono text-xs text-emerald-300/90 break-all select-all border border-zinc-800/80 max-h-36 overflow-y-auto">
                  {syncString}
                </div>
              </div>

              <button
                onClick={handleCopyCode}
                className={`w-full flex items-center justify-center gap-2 py-3 rounded-xl font-bold text-sm transition-all shadow-lg ${
                  copiedCode
                    ? 'bg-emerald-600 text-white'
                    : 'bg-emerald-500 hover:bg-emerald-400 text-zinc-950 shadow-emerald-500/20'
                }`}
              >
                {copiedCode ? <Check className="w-4 h-4 stroke-[2.5]" /> : <Copy className="w-4 h-4" />}
                <span>{copiedCode ? t('sync_copied_code') : t('sync_copy_code')}</span>
              </button>
            </div>
          )}

          {/* TAB 3: FILE .ITIMER.JSON */}
          {activeTab === 'file' && (
            <div className="w-full flex flex-col gap-4">
              {/* Target Selector */}
              <div className="flex items-center justify-center gap-2 bg-zinc-900/80 p-1.5 rounded-xl border border-zinc-800 text-xs">
                <button
                  onClick={() => setSyncTarget('current')}
                  className={`flex-1 py-1.5 px-3 rounded-lg font-semibold transition-all ${
                    syncTarget === 'current'
                      ? 'bg-zinc-800 text-white shadow-sm'
                      : 'text-zinc-400 hover:text-zinc-200'
                  }`}
                >
                  {t('sync_target_current')} («{targetPlan.name}»)
                </button>
                <button
                  onClick={() => setSyncTarget('all')}
                  className={`flex-1 py-1.5 px-3 rounded-lg font-semibold transition-all ${
                    syncTarget === 'all'
                      ? 'bg-zinc-800 text-white shadow-sm'
                      : 'text-zinc-400 hover:text-zinc-200'
                  }`}
                >
                  {t('sync_target_all')} ({allPresets.length})
                </button>
              </div>

              <div className="bg-zinc-900/60 rounded-2xl border border-zinc-800 p-4 font-mono text-xs text-zinc-400 max-h-48 overflow-y-auto leading-relaxed">
                <pre>{fullJson}</pre>
              </div>

              <button
                onClick={handleDownloadFile}
                className="w-full flex items-center justify-center gap-2 py-3 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-zinc-950 font-bold text-sm transition-all shadow-md shadow-emerald-500/20"
              >
                <Download className="w-4 h-4" />
                <span>{t('sync_download_itimer')}</span>
              </button>
            </div>
          )}

          {/* TAB 4: IMPORT FROM ANDROID */}
          {activeTab === 'import' && (
            <div className="w-full flex flex-col gap-4">
              {importStatus?.success && (
                <div className="bg-emerald-500/20 border border-emerald-500/50 rounded-xl p-3 text-xs text-emerald-300 flex items-center gap-2">
                  <Check className="w-4 h-4 text-emerald-400 shrink-0" />
                  <span>{importStatus.success}</span>
                </div>
              )}

              {importStatus?.error && (
                <div className="bg-rose-500/20 border border-rose-500/50 rounded-xl p-3 text-xs text-rose-300 flex items-center gap-2">
                  <X className="w-4 h-4 text-rose-400 shrink-0" />
                  <span>{importStatus.error}</span>
                </div>
              )}

              <div className="flex flex-col gap-2">
                <label className="text-xs font-semibold text-zinc-300">
                  {t('sync_import_title')}
                </label>
                <textarea
                  rows={4}
                  value={pasteInput}
                  onChange={(e) => setPasteInput(e.target.value)}
                  placeholder={t('sync_import_paste_placeholder')}
                  className="w-full bg-zinc-900 border border-zinc-700 rounded-xl p-3 text-xs font-mono text-white placeholder-zinc-500 focus:outline-none focus:border-emerald-500"
                />
              </div>

              <div className="flex items-center gap-3">
                <button
                  onClick={() => handleProcessImport(pasteInput)}
                  disabled={!pasteInput.trim()}
                  className="flex-1 flex items-center justify-center gap-2 py-2.5 rounded-xl bg-emerald-500 hover:bg-emerald-400 disabled:opacity-40 disabled:cursor-not-allowed text-zinc-950 font-bold text-xs transition-colors"
                >
                  <Upload className="w-4 h-4" />
                  <span>{t('sync_import_btn')}</span>
                </button>

                <label className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-zinc-900 border border-zinc-700 hover:bg-zinc-800 text-zinc-300 hover:text-white text-xs font-semibold transition-colors cursor-pointer">
                  <FileJson className="w-4 h-4 text-cyan-400" />
                  <span>{t('sync_import_file_label')}</span>
                  <input
                    type="file"
                    accept=".json,.itimer.json"
                    onChange={handleFileUpload}
                    className="hidden"
                  />
                </label>
              </div>
            </div>
          )}

          {/* Android Guide Footer Card */}
          <div className="w-full bg-zinc-900/50 border border-zinc-800/80 rounded-2xl p-4 flex items-start gap-3 text-xs mt-2">
            <Info className="w-5 h-5 text-cyan-400 shrink-0 mt-0.5" />
            <div>
              <strong className="text-zinc-200 block font-semibold mb-0.5">
                {t('sync_android_guide_title')}
              </strong>
              <p className="text-zinc-400 leading-relaxed">
                {t('sync_android_guide_desc')}
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
