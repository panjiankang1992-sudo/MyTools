/** 确保媒体滚轮选择器提交当前可见值，而不是滞后的受控索引。 */
export class RemoteMediaPickerSelectionPolicy {
  /** 将滚轮回调的显示值和索引解析为稳定的原始值。 */
  resolve(value: string | string[], index: number | number[], rawOptions: string[],
    displayOptions: string[]): string {
    const displayValue = Array.isArray(value) ? (value[0] ?? '') : value;
    const valueIndex = displayOptions.indexOf(displayValue);
    if (valueIndex >= 0 && valueIndex < rawOptions.length) return rawOptions[valueIndex];
    const selectedIndex = Array.isArray(index) ? (index[0] ?? -1) : index;
    if (selectedIndex >= 0 && selectedIndex < rawOptions.length) return rawOptions[selectedIndex];
    return '';
  }
}
