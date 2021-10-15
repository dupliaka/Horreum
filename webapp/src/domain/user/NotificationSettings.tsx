import { useState } from "react"

import {
    Bullseye,
    Button,
    DataList,
    DataListItem,
    DataListItemRow,
    DataListItemCells,
    DataListCell,
    DataListAction,
    Form,
    FormGroup,
    Select,
    SelectOption,
    Spinner,
    TextInput,
    Title,
} from "@patternfly/react-core"

const EMPTY = { id: -1, method: "", data: "", disabled: false }

export type NotificationConfig = {
    id: number
    method: string
    data: string
    disabled: boolean
}

type NotificationSettingsProps = {
    settings: NotificationConfig
    methods: string[]
    onChange(): void
}

const NotificationSettings = ({ settings, methods, onChange }: NotificationSettingsProps) => {
    const [methodOpen, setMethodOpen] = useState(false)
    return (
        <Form isHorizontal={true} style={{ marginTop: "20px", width: "100%" }}>
            <FormGroup label="Method" fieldId="method">
                <Select
                    isDisabled={settings.disabled}
                    isOpen={methodOpen}
                    onToggle={open => setMethodOpen(open)}
                    selections={settings.method}
                    onSelect={(event, selection) => {
                        settings.method = selection.toString()
                        setMethodOpen(false)
                        onChange()
                    }}
                    placeholderText="Please select..."
                >
                    {methods.map((m, i) => (
                        <SelectOption key={i} value={m} />
                    ))}
                </Select>
            </FormGroup>
            <FormGroup label="Data" fieldId="data" helperText="e.g. email address, IRC channel...">
                <TextInput
                    isDisabled={settings.disabled}
                    id="data"
                    value={settings.data}
                    onChange={value => {
                        settings.data = value
                        onChange()
                    }}
                />
            </FormGroup>
        </Form>
    )
}

type NotificationSettingsListProps = {
    title: string
    data?: NotificationConfig[]
    methods: string[]
    onUpdate(data: NotificationConfig[]): void
}

export function NotificationSettingsList({ title, data, methods, onUpdate }: NotificationSettingsListProps) {
    if (data) {
        return (
            <>
                <div
                    style={{
                        marginTop: "16px",
                        marginBottom: "16px",
                        width: "100%",
                        display: "flex",
                        justifyContent: "space-between",
                    }}
                >
                    <Title headingLevel="h3">{title}</Title>
                    <Button onClick={() => onUpdate([...data, { ...EMPTY }])}>Add notification</Button>
                </div>
                <DataList aria-label="List of settings">
                    {data.map((s, i) => (
                        <DataListItem key={i} aria-labelledby="">
                            <DataListItemRow>
                                <DataListItemCells
                                    dataListCells={[
                                        <DataListCell key="content">
                                            <NotificationSettings
                                                settings={s}
                                                methods={methods}
                                                onChange={() => onUpdate([...data])}
                                            />
                                        </DataListCell>,
                                    ]}
                                />
                                <DataListAction
                                    style={{
                                        flexDirection: "column",
                                        justifyContent: "center",
                                    }}
                                    id="delete"
                                    aria-labelledby="delete"
                                    aria-label="Settings actions"
                                    isPlainButtonAction
                                >
                                    <Button
                                        onClick={() => {
                                            s.disabled = !s.disabled
                                            onUpdate([...data])
                                        }}
                                    >
                                        {s.disabled ? "Enable" : "Disable"}
                                    </Button>
                                    <Button
                                        variant="secondary"
                                        onClick={() => {
                                            data.splice(i, 1)
                                            onUpdate([...data])
                                        }}
                                    >
                                        Delete
                                    </Button>
                                </DataListAction>
                            </DataListItemRow>
                        </DataListItem>
                    ))}
                </DataList>
            </>
        )
    } else {
        return (
            <Bullseye>
                <Spinner />
            </Bullseye>
        )
    }
}